package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.MempoolAdmissionException;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.BlockDownloadScheduler;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded unsolicited-message processing and relay. No disk work on peer reader threads.
 */
public final class NodeRelayService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NodeRelayService.class);
    private static final long MSG_WTX = 5;
    private static final long MAX_QUEUED_INBOUND_BYTES = 16_000_000L;
    private static final long MAX_QUEUED_INBOUND_BYTES_PER_PEER = 2_000_000L;
    private static final int MAX_OUTBOUND_TASKS_PER_PEER = 64;
    private static final long MAX_OUTBOUND_BYTES_PER_PEER = 16_000_000L;
    private static final long MAX_OUTBOUND_BYTES = 64_000_000L;

    // Core 32.1 keeps per-peer randomized trickle after global rate limiting.
    static final long INBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS =
            Duration.ofSeconds(5).toNanos();
    static final long OUTBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS =
            Duration.ofSeconds(2).toNanos();
    static final int INVENTORY_BROADCAST_PER_SECOND = 14;

    // BIP133 / Bitcoin Core v31.1.
    static final int FEEFILTER_VERSION = 70_013;
    static final long AVG_FEEFILTER_BROADCAST_INTERVAL_NANOS =
            Duration.ofMinutes(10).toNanos();
    static final long MAX_FEEFILTER_CHANGE_DELAY_NANOS =
            Duration.ofMinutes(5).toNanos();

    private final AtomicLong outboundBytes = new AtomicLong();
    private final TransactionRelayBuckets inventoryBuckets;

    private final NodeValidationService validation;
    private final boolean blocksOnly;
    private final boolean coreHeadersPolicy;
    private final NodeSyncInfrastructure sync;
    private final PeerManager peers;
    private final BlockDownloadScheduler blockDownloadScheduler;
    private final Set<Peer> attached = ConcurrentHashMap.newKeySet();
    private final Map<Peer, PeerOutbound> outbound = new ConcurrentHashMap<>();
    private final Map<Peer, BlockAnnouncementState> blockAnnouncements = new ConcurrentHashMap<>();
    private final Map<Peer, TxRelayState> txRelayStates = new ConcurrentHashMap<>();
    private final PendingCompactBlocks pendingCompactBlocks = new PendingCompactBlocks();
    private volatile Block mostRecentBlock;
    // Guarded by the map monitor. These are relay-owned fallbacks only; a hash already
    // owned by BlockDownloadScheduler must stay in the scheduler's single download lifecycle.
    private final Map<Peer, Map<Hash256, Long>> compactFallbacks = new HashMap<>();
    private static final int MAX_COMPACT_FALLBACKS = 256;
    private static final int MAX_COMPACT_FALLBACKS_PER_PEER = 16;
    private final Deque<Peer> highBandwidthCompactPeers = new ArrayDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final Map<Peer, PeerInbound> inbound = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sendTimeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-relay-send-timeout").factory());
    private final ScheduledExecutorService requestTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-tx-request-timer").factory());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("bitcoin-relay").factory());
    private final AtomicBoolean requestTickQueued = new AtomicBoolean();
    private final TransactionRequestScheduler transactionRequests = new TransactionRequestScheduler();
    private final FeeFilterRounder feeFilterRounder =
            new FeeFilterRounder(
                    1_000L,
                    ThreadLocalRandom.current()
            );
    private final TxOrphanage orphanage = new TxOrphanage();
    private final LinkedHashMap<Hash256, Boolean> announcedBlocks = new LinkedHashMap<>();
    private static final int MAX_RECENT_BLOCK_ANNOUNCEMENTS = 4096;
    // Bitcoin Core v31.1 MAX_BLOCKS_TO_ANNOUNCE.
    private static final int MAX_BLOCKS_TO_ANNOUNCE = 8;
    // Bitcoin Core v31.1 BIP152 serving depths.
    private static final long CMPCTBLOCKS_VERSION = 2;
    // BIP152/Core keeps at most three peers in high-bandwidth announcement mode.
    private static final int MAX_HIGH_BANDWIDTH_COMPACT_PEERS = 3;
    private static final long COMPACT_BLOCK_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private final PeerMessageListener messages = this::enqueue;
    private final Consumer<Peer> connections = this::attach;
    private volatile boolean closed;
    private volatile long lastTipUpdateSeconds;
    private final Map<Peer, Hash256> validatedPeerHeaders = new ConcurrentHashMap<>();
    private Hash256 recoveryLocatorTip;
    private List<Hash256> recoveryLocator = List.of();

    private java.math.BigInteger knownPeerWork(Peer peer) {
        java.math.BigInteger work = null;
        var validated = validatedPeerHeaders.get(peer);
        if (validated != null) {
            var index = sync.blockIndexLookup().find(validated);
            if (index != null && !sync.downloadBlockFailed(index)) work = index.chainWork();
        }
        var hash = peer.lastBlockAnnouncement();
        if (hash != null) {
            var index = sync.blockIndexLookup().find(hash);
            if (index != null && !sync.downloadBlockFailed(index))
                work = work == null ? index.chainWork() : work.max(index.chainWork());
        }
        return work;
    }

    private synchronized List<Hash256> recoveryLocator(ru.bitcoin.node.chain.BlockIndex tip) {
        if (!tip.hash().equals(recoveryLocatorTip)) {
            if (tip.height() == 0) recoveryLocator = List.of();
            else {
                var parent = sync.blockIndexLookup().find(tip.previousBlockHash());
                recoveryLocator = List.copyOf(sync.blockLocatorBuilder().build(Objects.requireNonNull(parent)));
            }
            recoveryLocatorTip = tip.hash();
        }
        return recoveryLocator;
    }

    public OutboundPeerRecoveryView outboundRecoveryView() {
        return new OutboundPeerRecoveryView() {
            public boolean headersSyncStarted(Peer peer) { return !coreHeadersPolicy || peers.headersSyncStarted(peer); }
            public long lastTipUpdateSeconds() { return lastTipUpdateSeconds; }
            public boolean enabled() { return !closed; }
            public boolean blocksInFlight() {
                synchronized (compactFallbacks) {
                    return (blockDownloadScheduler != null && blockDownloadScheduler.blocksInFlight())
                            || pendingCompactBlocks.hasPending()
                            || compactFallbacks.values().stream().anyMatch(values -> !values.isEmpty());
                }
            }
            public boolean blocksInFlight(Peer peer) {
                synchronized (compactFallbacks) {
                    return (blockDownloadScheduler != null && blockDownloadScheduler.blocksInFlight(peer))
                            || pendingCompactBlocks.hasPending(peer)
                            || !compactFallbacks.getOrDefault(peer, Map.of()).isEmpty();
                }
            }
            public boolean hasTipWork(Peer peer) {
                var work = knownPeerWork(peer);
                return work != null && work.compareTo(validation.downloadTip().chainWork()) >= 0;
            }
            public boolean protectedFromChainSync(Peer peer) { return peers.hasChainSyncProtection(peer); }
            public boolean directFetch() {
                return validation.downloadTip().header().timestamp().value() > System.currentTimeMillis() / 1000
                        - validation.networkParameters().targetSpacingSeconds() * 20;
            }
            public ChainWork chainWork(Peer peer) {
                var tip = validation.downloadTip();
                return new ChainWork(tip.chainWork(), knownPeerWork(peer), recoveryLocator(tip));
            }
            public void requestInitialHeaders(Peer peer, List<Hash256> locator) {
                // Initial/live synchronizers observe every READY peer and own
                // initial admission, preferred-source selection and its timer.
                // A supervisor send here would bypass that shared policy.
            }
            public void requestHeaders(Peer peer, List<Hash256> locator) throws IOException {
                peer.sendAsync(BitcoinMessages.getHeaders(new GetHeadersMessage(
                        Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                        locator, new Hash256(new byte[32]))));
            }
        };
    }

    private ru.bitcoin.node.chain.BlockIndex processPeerHeaders(Peer peer, HeadersMessage announcement) {
        var reception = validation.processHeadersWithReception(sync.headerSyncService(), announcement);
        return updatePeerHeaders(peer, reception);
    }

    private ru.bitcoin.node.chain.BlockIndex updatePeerHeaders(Peer peer,
            ru.bitcoin.node.chain.HeaderBatchProcessor.Reception reception) {
        if (!reception.indexes().isEmpty()) {
            var index = reception.indexes().getLast();
            if (index != null && !sync.downloadBlockFailed(index)) {
                synchronized (validatedPeerHeaders) {
                    var previousHash = validatedPeerHeaders.get(peer);
                    var previous = previousHash == null ? null : sync.blockIndexLookup().find(previousHash);
                    if (attached.contains(peer) && (previous == null || sync.downloadBlockFailed(previous)
                            || index.chainWork().compareTo(previous.chainWork()) >= 0))
                        validatedPeerHeaders.put(peer, index.hash());
                }
                if (reception.lastHeaderNew() && index.chainWork().compareTo(validation.downloadTip().chainWork()) > 0)
                    peer.recordNewBlockAnnouncement();
                var bestWork = knownPeerWork(peer);
                if (bestWork != null && bestWork.compareTo(validation.downloadTip().chainWork()) >= 0) peers.protectChainSync(peer);
                return index;
            }
        }
        return null;
    }


    /**
     * Per-peer BIP130 block-announcement preference and last header sent.
     */
    private static final class BlockAnnouncementState {
        private final java.util.concurrent.atomic.AtomicReference<Hash256> continuation = new java.util.concurrent.atomic.AtomicReference<>();
        private volatile boolean prefersHeaders;
        private volatile Hash256 bestHeaderSent;
        private volatile boolean providesCompactBlocks;
        private volatile boolean requestsHighBandwidthCompactBlocks;
    }

    public NodeRelayService(NodeValidationService validation, NodeSyncInfrastructure sync, PeerManager peers) {
        this(validation, sync, peers, null);
    }

    public NodeRelayService(
            NodeValidationService validation,
            NodeSyncInfrastructure sync,
            PeerManager peers,
            BlockDownloadScheduler blockDownloadScheduler
    ) {
        this(validation, sync, peers, blockDownloadScheduler, INVENTORY_BROADCAST_PER_SECOND);
    }

    public NodeRelayService(NodeValidationService validation, NodeSyncInfrastructure sync, PeerManager peers,
                            BlockDownloadScheduler blockDownloadScheduler, int transactionSendRate) {
        this(validation, sync, peers, blockDownloadScheduler, transactionSendRate, false);
    }

    public NodeRelayService(NodeValidationService validation, NodeSyncInfrastructure sync, PeerManager peers,
                            BlockDownloadScheduler blockDownloadScheduler, int transactionSendRate, boolean blocksOnly) {
        this(validation, sync, peers, blockDownloadScheduler, transactionSendRate, blocksOnly, true);
    }

    public NodeRelayService(NodeValidationService validation, NodeSyncInfrastructure sync, PeerManager peers,
                            BlockDownloadScheduler blockDownloadScheduler, int transactionSendRate, boolean blocksOnly,
                            boolean coreHeadersPolicy) {
        this.blocksOnly = blocksOnly;
        this.coreHeadersPolicy = coreHeadersPolicy;
        inventoryBuckets = new TransactionRelayBuckets(transactionSendRate);
        this.validation = Objects.requireNonNull(validation, "validation");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.blockDownloadScheduler = blockDownloadScheduler;
        peers.addPeerListener(connections);
        requestTimer.scheduleWithFixedDelay(this::enqueueRequestTick, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void enqueueRequestTick() {
        if (closed || !requestTickQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    if (!closed) {
                        if (!validation.downloadInitialBlockDownload()) {
                            dispatchTransactionRequests();
                            maybeSendFeeFilters();
                        }
                        flushTransactionInventory();
                        expireCompactBlocks();
                    }
                } finally { requestTickQueued.set(false); }
            });
        } catch (RejectedExecutionException ignored) {
            requestTickQueued.set(false);
        }
    }

    private void attach(Peer peer) {
        if (!closed && attached.add(peer)) {
            inbound.computeIfAbsent(peer, PeerInbound::new);
            outbound.computeIfAbsent(peer, PeerOutbound::new);
            blockAnnouncements.computeIfAbsent(peer, ignored -> new BlockAnnouncementState());
            txRelayStates.computeIfAbsent(peer, ignored -> new TxRelayState());
            pendingCompactBlocks.register(peer);
            peer.addMessageListener(messages);

            peer.addCloseListener((source, cause) -> {
                source.removeMessageListener(messages);
                attached.remove(source);
                PeerOutbound sender = outbound.remove(source);
                blockAnnouncements.remove(source);
                txRelayStates.remove(source);
                synchronized (validatedPeerHeaders) { validatedPeerHeaders.remove(source); }
                pendingCompactBlocks.removePeer(source);
                synchronized (compactFallbacks) { compactFallbacks.remove(source); }
                removeHighBandwidthCompactPeer(source);
                orphanage.removePeer(source);
                PeerInbound receiver = inbound.remove(source);
                if (receiver != null) receiver.stop();
                if (sender != null) sender.shutdownNow();
            });
        }
    }

    private void enqueue(Peer peer, BitcoinMessage message) {
        // Ignore transaction traffic before allocating relay tasks during IBD.
        // Block inventory has its independent header/download listeners.
        if (validation.downloadInitialBlockDownload()
                && ("inv".equals(message.command()) || "tx".equals(message.command()))
                && !(blocksOnly && !peer.hasPermission(PeerPermission.RELAY))) return;
        if ("block".equals(message.command())) {
            synchronized (compactFallbacks) {
                if (!compactFallbacks.containsKey(peer)) return;
            }
        }
        if (closed || !Set.of("inv", "tx", "getdata", "getblocks", "getheaders", "headers", "notfound", "sendheaders", "sendcmpct", "feefilter", "cmpctblock", "getblocktxn", "blocktxn", "block", "filterload", "filteradd", "filterclear", "mempool").contains(message.command()))
            return;
        PeerInbound receiver = inbound.get(peer);
        if (receiver != null) receiver.enqueue(message);
    }

    private void closeForLocalOverload(Peer peer, String message, Throwable failure) {
        try {
            peer.close(new PeerCloseException(PeerCloseReason.LOCAL_RESOURCE_LIMIT,
                    "NodeRelayService.enqueue", message, failure));
        } catch (IOException closeFailure) { log.debug("Unable to close overloaded peer", closeFailure); }
    }

    /** One scheduled drain per peer, rather than one executor task per message. */
    private final class PeerInbound {
        private static final int MAX_MESSAGES = 1024;
        private static final int DRAIN_BATCH = 16;
        private final Peer peer;
        private final Deque<BitcoinMessage> messages = new ArrayDeque<>();
        private long bytes;
        private boolean scheduled;
        private boolean stopped;

        PeerInbound(Peer peer) { this.peer = peer; }

        void enqueue(BitcoinMessage message) {
            boolean overloaded = false;
            synchronized (this) {
                if (stopped || closed || !peer.isReady()) return;
                long charge = charge(message);
                if (messages.size() >= MAX_MESSAGES || bytes + charge > MAX_QUEUED_INBOUND_BYTES_PER_PEER) {
                    overloaded = true;
                } else if (queuedBytes.addAndGet(charge) > MAX_QUEUED_INBOUND_BYTES) {
                    queuedBytes.addAndGet(-charge);
                    overloaded = true;
                } else {
                    bytes += charge;
                    messages.addLast(message);
                    if (!scheduled) {
                        scheduled = true;
                        overloaded = !schedule();
                    }
                }
            }
            if (overloaded) closeForLocalOverload(peer, "Inbound relay queue budget exhausted", null);
        }

        private boolean schedule() {
            try { worker.execute(this::drain); return true; }
            catch (RejectedExecutionException exception) {
                stop();
                return false;
            }
        }

        private void drain() {
            for (int i = 0; i < DRAIN_BATCH; i++) {
                BitcoinMessage message;
                synchronized (this) {
                    message = messages.pollFirst();
                    if (message == null || stopped) { scheduled = false; return; }
                }
                try {
                    if (!closed && peer.isReady()) handle(peer, message);
                } catch (IOException | IllegalArgumentException exception) {
                    log.debug("Rejected peer message {}", message.command(), exception);
                    peer.disconnectForProtocolViolation("Rejected " + message.command(), exception);
                } catch (RuntimeException exception) {
                    log.error("Unable to process peer message {}", message.command(), exception);
                } finally {
                    synchronized (this) { bytes -= charge(message); }
                    queuedBytes.addAndGet(-charge(message));
                }
            }
            boolean rejected = false;
            synchronized (this) {
                if (stopped || messages.isEmpty()) scheduled = false;
                else rejected = !schedule(); // Yield to other peers and relay maintenance.
            }
            if (rejected && !closed) closeForLocalOverload(peer, "Inbound relay drain queue saturated", null);
        }

        synchronized void stop() {
            stopped = true;
            for (BitcoinMessage message : messages) {
                bytes -= charge(message);
                queuedBytes.addAndGet(-charge(message));
            }
            messages.clear();
        }

        private long charge(BitcoinMessage message) { return 128L + message.payloadLength(); }
    }

    private void handle(Peer peer, BitcoinMessage message) throws IOException {
        expireCompactBlocks();
        PeerConnectionRole role = peers.roleOf(peer);
        switch (message.command()) {
            case "inv" -> {
                if (role.relaysTransactions() && (!validation.downloadInitialBlockDownload()
                        || (blocksOnly && !peer.hasPermission(PeerPermission.RELAY)))) {
                    InvMessage inventory = BitcoinMessages.decodeInv(message);
                    if (blocksOnly && !peer.hasPermission(PeerPermission.RELAY)
                            && inventory.inventory().stream().anyMatch(vector -> vector.type() == InventoryVector.MSG_TX
                            || vector.type() == InventoryVector.MSG_WITNESS_TX || vector.type() == MSG_WTX)) {
                        disconnect(peer);
                        break;
                    }
                    if (validation.downloadInitialBlockDownload()) break;
                    TxRelayState relayState = txRelayStates.get(peer);
                    if (relayState != null) {
                        for (InventoryVector vector : inventory.inventory()) {
                            if (vector.type() == InventoryVector.MSG_TX
                                    || vector.type() == InventoryVector.MSG_WITNESS_TX
                                    || vector.type() == MSG_WTX) {
                                relayState.markKnown(vector.hash());
                                validation.acknowledgeMempoolBroadcast(vector.hash());
                            }
                        }
                    }
                    requestTransactions(peer, inventory);
                }
            }
            case "tx" -> {
                if (blocksOnly && !peer.hasPermission(PeerPermission.RELAY)) disconnect(peer);
                else if (role.relaysTransactions() && !validation.downloadInitialBlockDownload())
                    receiveTransaction(peer, TransactionParser.parse(message.payload()));
            }
            case "mempool" -> {
                if ((peer.localServices() & VersionMessage.NODE_BLOOM) == 0
                        && !peer.hasPermission(PeerPermission.MEMPOOL)) {
                    if (!peer.hasPermission(PeerPermission.NOBAN)) disconnect(peer);
                    break;
                }
                if (role.relaysTransactions()) {
                    var state = txRelayStates.get(peer);
                    if (state != null) state.requestMempool();
                }
            }
            case "getdata" -> queueGetData(peer, BitcoinMessages.decodeGetData(message));
            case "headers" -> {
                var announcement = BitcoinMessages.decodeHeaders(message);
                var batch = announcement.headers();
                // Core checks the entire announcement's claimed work before
                // treating a missing parent as a benign request for deeper headers.
                for (var header : batch) {
                    if (!ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(header, validation.networkParameters()))
                        throw new IllegalArgumentException("Header announcement has invalid proof of work");
                }
                for (int i = 1; i < batch.size(); i++) {
                    if (!batch.get(i).previousBlockHash().equals(batch.get(i - 1).hash())) {
                        throw new IllegalArgumentException("Non-continuous headers announcement");
                    }
                }
                try {
                    // Availability responses use sendAsync rather than a pending HEADERS
                    // future. Validate/persist them even while historical blocks download.
                    var result = validation.processPeerHeadersWithReception(sync.headerSyncService(), peer,
                            announcement, peer::headersResponseValidated);
                    var last = updatePeerHeaders(peer, result.reception());
                    if (!result.nextLocator().isEmpty()) {
                        send(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                                Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                                result.nextLocator(), new Hash256(new byte[32]))));
                    }
                    if (!batch.isEmpty()) {
                        if (!result.presyncHandled() && batch.size() == HeadersMessage.MAX_HEADERS && last != null) {
                            // Asynchronous availability replies have no coordinator
                            // to advance their locator. Full replies may have more.
                            send(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                                    Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                                    sync.blockLocatorBuilder().build(last), new Hash256(new byte[32]))));
                        }
                    }
                } catch (ru.bitcoin.node.chain.UnconnectedHeaderException unconnected) {
                    requestMissingHeaders(peer);
                    peer.noteBlockAnnouncement(batch.getLast().hash());
                } catch (ru.bitcoin.node.consensus.block.BlockHeaderValidationException invalid) {
                    handleInvalidHeader(peer, invalid, false);
                }
            }
            case "filterload", "filteradd", "filterclear" -> {
                // Core checks the offered service before decoding any Bloom payload.
                if ((peer.localServices() & VersionMessage.NODE_BLOOM) == 0) disconnect(peer);
            }
            case "getblocks" -> {
                GetBlocksMessage request;
                try { request = BitcoinMessages.decodeGetBlocks(message); }
                catch (GetHeadersMessageCodec.TooManyLocatorHashesException oversized) { disconnect(peer); break; }
                var inventory = validation.blockInventory(request.locatorHashes(), request.stopHash());
                BlockAnnouncementState state = blockAnnouncements.get(peer);
                if (state != null && inventory.reachedLimit()) state.continuation.set(inventory.hashes().getLast());
                if (!inventory.hashes().isEmpty()) send(peer, BitcoinMessages.inv(new InvMessage(inventory.hashes().stream()
                        .map(hash -> new InventoryVector(InventoryVector.MSG_BLOCK, hash)).toList())));
            }
            case "getheaders" -> {
                GetHeadersMessage request;
                try { request = GetHeadersMessageCodec.decode(message.payload()); }
                catch (GetHeadersMessageCodec.TooManyLocatorHashesException oversized) { disconnect(peer); break; }
                // Core returns an empty response below minimum chainwork. A
                // historical IBD tip must not scan/serve a low-work active prefix.
                if (!validation.downloadHasMinimumChainWork() && !peer.hasPermission(PeerPermission.DOWNLOAD)) {
                    send(peer, BitcoinMessages.headers(new HeadersMessage(List.of())));
                    break;
                }
                // A null locator requests exactly hashStop. Core silently ignores
                // unknown/ineligible hashes instead of returning an empty HEADERS.
                if (request.locatorHashes().isEmpty()) {
                    var header = validation.requestedHeader(request.stopHash());
                    if (header.isEmpty()) break;
                    send(peer, BitcoinMessages.headers(new HeadersMessage(List.of(header.get()))));
                    BlockAnnouncementState state = blockAnnouncements.get(peer);
                    if (state != null) state.bestHeaderSent = header.get().hash();
                    break;
                }
                var headers = validation.headers(request.locatorHashes(), request.stopHash());
                send(peer, BitcoinMessages.headers(new HeadersMessage(headers)));
                BlockAnnouncementState state = blockAnnouncements.get(peer);
                if (state != null) {
                    if (!headers.isEmpty()) {
                        state.bestHeaderSent = headers.getLast().hash();
                    } else {
                        validation.bestActiveLocator(request.locatorHashes())
                                .ifPresent(hash -> state.bestHeaderSent = hash);
                    }
                }
            }
            case "feefilter" -> {
                long feeFilter = BitcoinMessages.decodeFeeFilter(message);

                /*
                 * Bitcoin Core ignores values outside MoneyRange rather than
                 * treating them as a protocol violation.
                 */
                if (Money.isValidAmount(feeFilter)) {
                    TxRelayState relayState = txRelayStates.get(peer);
                    if (relayState != null) {
                        relayState.feeFilterSatPerKvB(feeFilter);
                    }
                }
            }
            case "sendheaders" -> {
                if (message.payloadLength() != 0) {
                    throw new IllegalArgumentException("sendheaders message must have empty payload");
                }
                BlockAnnouncementState state = blockAnnouncements.get(peer);
                if (state != null) state.prefersHeaders = true;
            }
            case "sendcmpct" -> {
                SendCmpctMessage request = BitcoinMessages.decodeSendCmpct(message);
                // Core v31.1 supports witness compact blocks only (version 2).
                if (request.version() == CMPCTBLOCKS_VERSION) {
                    BlockAnnouncementState state = blockAnnouncements.get(peer);
                    if (state != null) {
                        state.providesCompactBlocks = true;
                        state.requestsHighBandwidthCompactBlocks = request.highBandwidth();
                    }
                }
            }
            case "cmpctblock" -> {
                var state = blockAnnouncements.get(peer);
                // Core ignores this command before decoding unless SENDCMPCT
                // established a supported version on this connection.
                if (state != null && state.providesCompactBlocks)
                    receiveCompactBlock(peer, BitcoinMessages.decodeCompactBlock(message, CMPCTBLOCKS_VERSION));
            }
            case "getblocktxn" -> queueGetBlockTxn(peer, BitcoinMessages.decodeGetBlockTxn(message));
            case "blocktxn" ->
                    receiveBlockTransactions(peer, BitcoinMessages.decodeBlockTxn(message, CMPCTBLOCKS_VERSION));
            case "block" -> receiveCompactFallback(peer, message);
            case "notfound" -> {
                for (var vector : BitcoinMessages.decodeNotFound(message).inventory()) {
                    transactionRequests.notFound(peer, vector.hash());
                    if (vector.type() == InventoryVector.MSG_BLOCK || vector.type() == InventoryVector.MSG_WITNESS_BLOCK) {
                        takeCompactFallback(peer, vector.hash());
                    }
                }
                dispatchTransactionRequests();
            }
            default -> {
            }
        }
    }

    private void promoteHighBandwidthCompactPeer(Peer peer) {
        BlockAnnouncementState state = blockAnnouncements.get(peer);
        if (state == null || !state.providesCompactBlocks || closed) return;

        Peer demoted = null;
        synchronized (highBandwidthCompactPeers) {
            if (highBandwidthCompactPeers.remove(peer)) {
                highBandwidthCompactPeers.addLast(peer);
                return;
            }
            if (highBandwidthCompactPeers.size() >= MAX_HIGH_BANDWIDTH_COMPACT_PEERS) {
                demoted = highBandwidthCompactPeers.removeFirst();
            }
            highBandwidthCompactPeers.addLast(peer);
        }
        if (demoted != null && demoted.isReady()) {
            send(demoted, BitcoinMessages.sendCmpct(new SendCmpctMessage(false, CMPCTBLOCKS_VERSION)));
        }
        send(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(true, CMPCTBLOCKS_VERSION)));
    }

    private void removeHighBandwidthCompactPeer(Peer peer) {
        synchronized (highBandwidthCompactPeers) {
            highBandwidthCompactPeers.remove(peer);
        }
    }

    private void requestMissingHeaders(Peer peer) {
        send(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                sync.blockLocatorBuilder().build(sync.headerChainState().bestHeaderTip()),
                new Hash256(new byte[32]))));
    }

    private static void handleInvalidHeader(Peer peer,
            ru.bitcoin.node.consensus.block.BlockHeaderValidationException invalid, boolean viaCompact) {
        var reason = invalid.reason();
        if (reason == ru.bitcoin.node.consensus.block.BlockHeaderValidationException.Reason.TIME_FUTURE
                || (reason == ru.bitcoin.node.consensus.block.BlockHeaderValidationException.Reason.CACHED_INVALID
                    && (peer.isInboundConnection() || viaCompact))) return;
        throw new IllegalArgumentException("Invalid announced block header", invalid);
    }

    private void receiveCompactBlock(Peer peer, CompactBlockMessage compact) {
        Hash256 hash = compact.header().hash();
        synchronized (compactFallbacks) {
            var requests = compactFallbacks.get(peer);
            if (requests != null && requests.containsKey(hash)) return;
        }
        if (pendingCompactBlocks.contains(peer, hash) || validation.findBlock(hash).isPresent()) return;

        // Core's compact-block missing-parent path precedes header validation
        // and only requests deeper headers when initial download has finished.
        if (sync.blockIndexLookup().find(compact.header().previousBlockHash()) == null) {
            if (!validation.downloadInitialBlockDownload()) requestMissingHeaders(peer);
            return;
        }
        if (!sync.headerSyncService().compactHasSufficientWork(compact.header())) return;

        // A compact block carries a real block header. Feed it through the existing header
        // validation/index pipeline before accepting any reconstructed body.
        try {
            processPeerHeaders(peer, new HeadersMessage(List.of(compact.header())));
        } catch (ru.bitcoin.node.consensus.block.BlockHeaderValidationException invalid) {
            handleInvalidHeader(peer, invalid, true);
            return;
        }

        List<Transaction> candidates = validation.mempoolEntries().stream()
                .map(entry -> entry.transaction()).toList();
        CompactBlockReconstruction.Partial partial =
                CompactBlockReconstruction.initialize(compact, candidates, CMPCTBLOCKS_VERSION);
        if (partial.complete()) {
            processReconstructedBlock(peer, partial.toBlock());
            return;
        }

        /*
         * The bulk scheduler already has an authoritative full-block request
         * lifecycle for this hash. We may optimistically reconstruct a compact
         * block without a round trip, but once transactions are missing we do
         * not start GETBLOCKTXN in parallel with that existing download.
         *
         * This mirrors the important Core invariant that compact and ordinary
         * block download share in-flight ownership instead of creating two
         * unrelated fallback lifecycles.
         */
        if (blockDownloadScheduler != null
                && blockDownloadScheduler.hasSubmittedBlock(hash)) {
            return;
        }

        var admission = pendingCompactBlocks.add(peer, hash, partial,
                System.nanoTime() + COMPACT_BLOCK_TIMEOUT_NANOS, PendingCompactBlocks.estimateBytes(partial));
        if (admission == PendingCompactBlocks.Admission.DUPLICATE) return;
        if (admission == PendingCompactBlocks.Admission.REJECTED) {
            if (attached.contains(peer)) requestFullBlock(peer, hash);
            return;
        }
        send(peer, BitcoinMessages.getBlockTxn(new BlockTransactionsRequest(hash, partial.missingIndexes())));
    }

    private void receiveBlockTransactions(Peer peer, BlockTransactionsMessage response) {
        var pending = pendingCompactBlocks.take(peer, response.blockHash());
        if (pending == null) return;
        if (System.nanoTime() - pending.expires() >= 0) {
            requestFullBlock(peer, response.blockHash());
            return;
        }
        Block block;
        try {
            block = pending.partial().fill(response.transactions());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // Reconstruction failure is not enough to blame the peer: mempool contents may have
            // changed or short IDs may have collided. Fall back to an ordinary witness block.
            requestFullBlock(peer, response.blockHash());
            return;
        }
        processReconstructedBlock(peer, block);
    }

    private void processReconstructedBlock(Peer source, Block block) {
        /*
         * A compact block may be the body currently awaited by the bulk block
         * scheduler. Let that existing lifecycle consume it first so the same
         * hash is not validated/downloaded through two independent paths.
         */
        if (blockDownloadScheduler != null) {
            if (blockDownloadScheduler.acceptBlock(source, block)) {
                promoteHighBandwidthCompactPeer(source);
                return;
            }

            /*
             * acceptBlock() is intentionally one-shot. A late duplicate compact
             * body can therefore arrive after the scheduler has already marked
             * the same submitted hash complete but before BlockSyncCoordinator
             * consumes and validates that completion. Keep that duplicate inside
             * the scheduler lifecycle instead of validating the same block through
             * the relay path as well.
             */
            if (blockDownloadScheduler.hasSubmittedBlock(block.hash())) {
                return;
            }
        }

        NodeValidationService.BlockReception reception;
        try {
            reception = validation.processBlockWithReception(block);
        } catch (ru.bitcoin.node.consensus.block.BlockValidationException exception) {
            // Both complete and partially reconstructed bodies can contain a short-ID collision.
            // Request authoritative full data; never treat storage/runtime failures as collisions.
            requestFullBlock(source, block.hash());
            return;
        }
        if (reception.newBody()) source.recordAcceptedBlock();
        if (reception.result() == BlockProcessingResult.CONNECTED) {
            promoteHighBandwidthCompactPeer(source);
            onConnectedBlockAfterReception(block, source);
        }
    }

    private void handleConnectedBlockOrphans(Block block) {
        for (Transaction transaction : block.transactions()) {
            orphanage.remove(transaction);
            orphanage.removeConflicts(transaction);
        }
        for (Transaction transaction : block.transactions()) {
            reconsiderOrphanDescendants(transaction);
        }
    }

    private void requestFullBlock(Peer peer, Hash256 hash) {
        /*
         * Never create a relay-owned GETDATA fallback on top of a block already
         * owned by the bulk scheduler. Its existing request/retry/timeout path
         * remains authoritative.
         */
        if (blockDownloadScheduler != null
                && blockDownloadScheduler.hasSubmittedBlock(hash)) {
            return;
        }

        synchronized (compactFallbacks) {
            if (closed || !attached.contains(peer)) return;
            var requests = compactFallbacks.computeIfAbsent(peer, ignored -> new HashMap<>());
            if (requests.containsKey(hash) || requests.size() >= MAX_COMPACT_FALLBACKS_PER_PEER
                    || compactFallbacks.values().stream().mapToInt(Map::size).sum() >= MAX_COMPACT_FALLBACKS) return;
            requests.put(hash, System.nanoTime() + COMPACT_BLOCK_TIMEOUT_NANOS);
        }
        send(peer, BitcoinMessages.getData(new GetDataMessage(List.of(
                new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, hash)))));
    }

    private boolean takeCompactFallback(Peer peer, Hash256 hash) {
        synchronized (compactFallbacks) {
            var requests = compactFallbacks.get(peer);
            Long deadline = requests == null ? null : requests.remove(hash);
            if (requests != null && requests.isEmpty()) compactFallbacks.remove(peer);
            return deadline != null && System.nanoTime() - deadline < 0;
        }
    }

    private void receiveCompactFallback(Peer peer, BitcoinMessage message) {
        synchronized (compactFallbacks) {
            if (!compactFallbacks.containsKey(peer)) return;
        }
        var header = BlockHeaderParser.parse(new BitcoinReader(message.payload()));
        if (!takeCompactFallback(peer, header.hash())) return;
        Block block = BlockParser.parse(message.payload());
        if (blockDownloadScheduler != null) {
            if (blockDownloadScheduler.acceptBlock(peer, block)) {
                promoteHighBandwidthCompactPeer(peer);
                return;
            }

            /*
             * A fallback response can race with another transport completing the
             * same scheduler-owned hash. Once the scheduler session has submitted
             * the hash, keep every late full-block response inside that lifecycle
             * until the session closes. This prevents a second consensus-validation
             * path after acceptBlock() has become one-shot false.
             */
            if (blockDownloadScheduler.hasSubmittedBlock(block.hash())) {
                return;
            }
        }
        // Full data uses ordinary consensus validation, without another reconstruction retry.
        var reception = validation.processBlockWithReception(block);
        if (reception.newBody()) peer.recordAcceptedBlock();
        if (reception.result() == BlockProcessingResult.CONNECTED) {
            promoteHighBandwidthCompactPeer(peer);
            onConnectedBlockAfterReception(block, peer);
        }
    }

    private void expireCompactBlocks() {
        long now = System.nanoTime();
        synchronized (compactFallbacks) {
            compactFallbacks.values().forEach(requests -> requests.values().removeIf(deadline -> now - deadline >= 0));
            compactFallbacks.values().removeIf(Map::isEmpty);
        }
        for (var expired : pendingCompactBlocks.expire(System.nanoTime())) {
            if (attached.contains(expired.peer())) requestFullBlock(expired.peer(), expired.hash());
        }
    }

    private void requestTransactions(Peer peer, InvMessage inventory) {
        Set<Hash256> known = new HashSet<>();
        for (var entry : validation.mempoolEntries()) {
            known.add(entry.transaction().txId());
            known.add(entry.transaction().wtxId());
        }

        long now = System.nanoTime();
        for (var vector : inventory.inventory()) {
            if (vector.type() != InventoryVector.MSG_TX && vector.type() != MSG_WTX) continue;
            if (known.contains(vector.hash())) continue;
            transactionRequests.announced(peer, vector, now);
        }
        dispatchTransactionRequests();
    }

    private void dispatchTransactionRequests() {
        List<TransactionRequestScheduler.Scheduled> scheduled = transactionRequests.schedule(
                System.nanoTime(),
                candidate -> candidate.isReady()
                        && attached.contains(candidate)
                        && peers.roleOf(candidate).relaysTransactions());
        if (scheduled.isEmpty()) return;

        LinkedHashMap<Peer, List<InventoryVector>> byPeer = new LinkedHashMap<>();
        for (var request : scheduled) {
            byPeer.computeIfAbsent(request.peer(), ignored -> new ArrayList<>()).add(request.vector());
        }

        for (var entry : byPeer.entrySet()) {
            List<InventoryVector> vectors = entry.getValue();
            for (int offset = 0; offset < vectors.size(); offset += TransactionRequestScheduler.MAX_GETDATA_BATCH) {
                int end = Math.min(offset + TransactionRequestScheduler.MAX_GETDATA_BATCH, vectors.size());
                send(entry.getKey(), BitcoinMessages.getData(
                        new GetDataMessage(List.copyOf(vectors.subList(offset, end)))));
            }
        }
    }

    private void receiveTransaction(Peer peer, Transaction transaction) throws IOException {
        transactionRequests.forget(transaction.txId(), transaction.wtxId());
        var state = txRelayStates.get(peer);
        if (state != null) { state.markKnown(transaction.txId()); state.markKnown(transaction.wtxId()); }
        // Core's ReceivedTx accepts new unsolicited transactions for validation.
        // A force-relay duplicate must use the already validated mempool object.
        var existing = validation.mempoolEntry(transaction.txId());
        if (existing.isPresent()) {
            var accepted = existing.get().transaction();
            if (peer.hasPermission(PeerPermission.FORCERELAY) && accepted.wtxId().equals(transaction.wtxId()))
                announceTransaction(accepted, peer);
            return;
        }
        try {
            validation.admit(transaction);
            peer.recordAcceptedTransaction();
            announceTransaction(transaction, peer);
            reconsiderOrphanDescendants(transaction);
        } catch (MempoolAdmissionException exception) {
            if (isMissingInput(exception) && orphanage.add(transaction, peer)) {
                requestTransactions(peer, new InvMessage(transaction.inputs().stream()
                        .map(input -> new InventoryVector(InventoryVector.MSG_TX, input.previousOutput().transactionId()))
                        .distinct().toList()));
            } else {
                tryOpportunisticOneParentOneChildPackage(peer, transaction);
            }
        } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                 | ru.bitcoin.node.script.ScriptExecutionException
                 | ru.bitcoin.node.script.ScriptParseException exception) {
            log.debug("Rejected invalid transaction {}", transaction.txId());
        }
    }

    /**
     * Core-style opportunistic 1-parent-1-child package acceptance.  A child may
     * arrive first and be kept in the orphanage while its parent is individually
     * below the relay/mempool feerate floor.  When that parent arrives, evaluate
     * parent+child atomically so the child's fee can pay for the parent.
     */
    private boolean tryOpportunisticOneParentOneChildPackage(Peer source, Transaction parent) {
        for (Transaction child : orphanage.childrenOf(parent.txId())) {
            try {
                validation.admitPackage(List.of(parent, child));
                source.recordAcceptedTransaction();
                orphanage.remove(child);
                announceTransaction(parent, source);
                announceTransaction(child, source);
                reconsiderOrphanDescendants(child);
                return true;
            } catch (MempoolAdmissionException
                     | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException
                     | ru.bitcoin.node.script.ScriptParseException ignored) {
                // Another orphan may form a valid 1P1C package with this parent.
            }
        }
        return false;
    }

    private void reconsiderOrphanDescendants(Transaction acceptedParent) {
        ArrayDeque<Hash256> acceptedParents = new ArrayDeque<>();
        acceptedParents.add(acceptedParent.txId());
        while (!acceptedParents.isEmpty()) {
            Hash256 parentTxid = acceptedParents.removeFirst();
            for (Transaction orphan : orphanage.childrenOf(parentTxid)) {
                try {
                    validation.admit(orphan);
                    var supplier = orphanage.sourceOf(orphan);
                    if (supplier != null) supplier.recordAcceptedTransaction();
                    orphanage.remove(orphan);
                    announceTransaction(orphan, null);
                    acceptedParents.addLast(orphan.txId());
                } catch (MempoolAdmissionException exception) {
                    if (!isMissingInput(exception)) orphanage.remove(orphan);
                } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                         | ru.bitcoin.node.script.ScriptExecutionException
                         | ru.bitcoin.node.script.ScriptParseException exception) {
                    orphanage.remove(orphan);
                }
            }
        }
    }

    private static boolean isMissingInput(MempoolAdmissionException exception) {
        return exception.getMessage() != null && exception.getMessage().startsWith("Missing UTXO:");
    }

    /**
     * GETDATA is valid up to the protocol inventory limit. Keep the complete request as one
     * bounded per-peer task instead of expanding it into tens of thousands of queued send tasks.
     */
    private void queueGetData(Peer peer, GetDataMessage request) {
        PeerOutbound sender = outbound.get(peer);
        // Charge decoded vectors/hash arrays/list references as well as the task itself.
        long retainedBytes = 256L + 128L * request.inventory().size();
        if (sender == null || !sender.execute(new GetDataWork(peer, request), retainedBytes)) {
            disconnect(peer);
        }
    }

    private void queueGetBlockTxn(Peer peer, BlockTransactionsRequest request) {
        if (request.indexes().isEmpty()) {
            disconnect(peer); // Core disconnects without discouraging this request.
            return;
        }
        PeerOutbound sender = outbound.get(peer);
        long retainedBytes = 256L + 8L * request.indexes().size();
        if (sender == null || !sender.execute(() -> serveBlockTransactions(peer, request), retainedBytes)) {
            disconnect(peer);
        }
    }

    private void serveBlockTransactions(Peer peer, BlockTransactionsRequest request) throws IOException {
        var recent = mostRecentBlock;
        if (recent != null && recent.hash().equals(request.blockHash())) {
            sendBlockTransactions(peer, request, recent);
            return;
        }
        Optional<NodeValidationService.BlockTransactionsResponse> response;
        try { response = peer.hasPermission(PeerPermission.NOBAN)
                ? validation.requestedBlockTransactions(request.blockHash(), peer.localServices(), true)
                : validation.requestedBlockTransactions(request.blockHash(), peer.localServices()); }
        catch (NodeValidationService.LimitedHistoryRequestException denied) { disconnect(peer); return; }
        if (response.isEmpty()) return;
        Block block = response.get().block();

        if (response.get().fullBlock()) {
            sendDirect(peer, new BitcoinMessage("block", BlockSerializer.serialize(block)));
            sendContinuation(peer, request.blockHash());
            return;
        }

        sendBlockTransactions(peer, request, block);
    }

    private void sendBlockTransactions(Peer peer, BlockTransactionsRequest request, Block block) throws IOException {
        List<Transaction> transactions = new ArrayList<>(request.indexes().size());
        for (int index : request.indexes()) {
            if (index < 0 || index >= block.transactions().size()) {
                peer.disconnectForProtocolViolation("getblocktxn transaction index out of bounds",
                        new IllegalArgumentException("Invalid transaction index: " + index));
                return;
            }
            transactions.add(block.transactions().get(index));
        }
        sendDirect(peer, BitcoinMessages.blockTxn(
                new BlockTransactionsMessage(request.blockHash(), transactions), CMPCTBLOCKS_VERSION));
    }

    /**
     * Runs only on this peer's outbound worker, so a slow socket cannot stall other peers.
     */
    private void serveData(Peer peer, GetDataMessage request, List<InventoryVector> missing) throws IOException {
        PeerConnectionRole role = peers.roleOf(peer);
        if (request.inventory().size() > GetDataMessage.MAX_INVENTORY_SIZE) {
            throw new IllegalArgumentException("getdata exceeds protocol inventory limit");
        }

        Map<Hash256, Transaction> byTxId = new HashMap<>();
        Map<Hash256, Transaction> byWtxId = new HashMap<>();
        boolean needsTransactions = !validation.downloadInitialBlockDownload()
                && role.relaysTransactions() && request.inventory().stream().anyMatch(vector ->
                vector.type() == InventoryVector.MSG_TX || vector.type() == InventoryVector.MSG_WITNESS_TX
                        || vector.type() == MSG_WTX);
        if (needsTransactions) {
            for (var entry : validation.mempoolEntries()) {
                Transaction transaction = entry.transaction();
                byTxId.put(transaction.txId(), transaction);
                byWtxId.put(transaction.wtxId(), transaction);
            }
        }

        for (var vector : request.inventory()) {
            if (!peer.isReady() || closed) return;

            if (vector.type() == InventoryVector.MSG_FILTERED_BLOCK) {
                if ((peer.localServices() & VersionMessage.NODE_BLOOM) == 0) { disconnect(peer); return; }
                // BIP37 serving is not implemented or advertised by the configured node.
                continue;
            }
            if (vector.type() == InventoryVector.MSG_CMPCT_BLOCK || vector.type() == InventoryVector.MSG_BLOCK
                    || vector.type() == InventoryVector.MSG_WITNESS_BLOCK) {
                Optional<NodeValidationService.BlockResponse> response;
                try { response = peer.hasPermission(PeerPermission.NOBAN)
                        ? validation.requestedBlock(vector.hash(), peer.localServices(), true)
                        : validation.requestedBlock(vector.hash(), peer.localServices()); }
                catch (NodeValidationService.LimitedHistoryRequestException denied) { disconnect(peer); return; }
                if (response.isPresent()) {
                    Block block = response.get().block();
                    if (vector.type() == InventoryVector.MSG_CMPCT_BLOCK && response.get().compactEligible()) {
                        long nonce = ThreadLocalRandom.current().nextLong();
                        sendDirect(peer, BitcoinMessages.compactBlock(
                                CompactBlockFactory.create(block, nonce, CMPCTBLOCKS_VERSION),
                                CMPCTBLOCKS_VERSION));
                    } else {
                        sendDirect(peer, new BitcoinMessage("block", vector.type() == InventoryVector.MSG_BLOCK
                                ? BlockSerializer.serializeLegacy(block) : BlockSerializer.serialize(block)));
                    }
                    sendContinuation(peer, vector.hash());
                }
                continue; // Missing, pruned or ineligible blocks are silently ignored by Core.
            } else if (role.relaysTransactions() && (vector.type() == InventoryVector.MSG_TX
                    || vector.type() == InventoryVector.MSG_WITNESS_TX
                    || vector.type() == MSG_WTX)) {
                Transaction transaction = vector.type() == MSG_WTX
                        ? byWtxId.get(vector.hash())
                        : byTxId.get(vector.hash());
                if (transaction != null) {
                    sendDirect(peer, new BitcoinMessage("tx", vector.type() == InventoryVector.MSG_TX
                            ? TransactionSerializer.serializeLegacy(transaction) : TransactionSerializer.serialize(transaction)));
                    continue;
                }
                missing.add(vector);
            }
        }

    }

    private void sendContinuation(Peer peer, Hash256 servedHash) throws IOException {
        var state = blockAnnouncements.get(peer);
        if (state == null) return;
        Hash256 marker = state.continuation.get();
        if (servedHash.equals(marker) && state.continuation.compareAndSet(marker, null)) {
            sendDirect(peer, BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(
                    InventoryVector.MSG_BLOCK, validation.downloadTip().hash())))));
        }
    }

    /** Retains one reservation while yielding between bounded portions of a GETDATA request. */
    private final class GetDataWork implements IoTask {
        private final Peer peer;
        private final List<InventoryVector> inventory;
        private final List<InventoryVector> missing = new ArrayList<>();
        private int offset;

        private GetDataWork(Peer peer, GetDataMessage request) {
            this.peer = peer;
            this.inventory = request.inventory();
        }

        @Override
        public void run() throws IOException {
            int end = offset;
            while (end < inventory.size() && end - offset < 128) {
                long type = inventory.get(end++).type();
                if (type == InventoryVector.MSG_BLOCK || type == InventoryVector.MSG_WITNESS_BLOCK
                        || type == InventoryVector.MSG_CMPCT_BLOCK || type == InventoryVector.MSG_FILTERED_BLOCK) break;
            }
            serveData(peer, new GetDataMessage(inventory.subList(offset, end)), missing);
            offset = end;
            if (complete() && !missing.isEmpty()) sendDirect(peer, BitcoinMessages.notFound(new NotFoundMessage(missing)));
        }

        private boolean complete() { return offset == inventory.size(); }
    }

    public BlockProcessingResult submitBlock(Block block) {
        var result = validation.processBlock(block);
        if (result == BlockProcessingResult.CONNECTED) {
            validation.processHeaders(sync.headerSyncService(), new HeadersMessage(List.of(block.header())));
            onConnectedBlock(block, null);
        }
        return result;
    }

    /**
     * Completes application-level work for a block that has already become active.
     * This is the single post-connect hook used by relay, RPC/local submission and
     * bulk block synchronization so orphan cleanup/reconsideration cannot be skipped.
     */
    public void onConnectedBlock(Block block, Peer source) {
        Objects.requireNonNull(block, "block");
        if (source != null) source.recordAcceptedBlock();
        onConnectedBlockAfterReception(block, source);
    }

    /** Active-block postprocessing when the reception path already accounted for source credit. */
    public void onConnectedBlockAfterReception(Block block, Peer source) {
        Objects.requireNonNull(block, "block");
        lastTipUpdateSeconds = System.currentTimeMillis() / 1000;
        handleConnectedBlockOrphans(block);
        relayConnectedBlock(block, source);
    }

    /**
     * Announces an active-tip block once, excluding the peer that supplied its body.
     */
    public void relayConnectedBlock(Block block, Peer source) {
        mostRecentBlock = Objects.requireNonNull(block);
        Objects.requireNonNull(block, "block");
        // Core's UpdatedBlockTip does not relay inventory during IBD. Feeding
        // every historical block into bounded send queues disconnects useful
        // download peers and spends I/O rebuilding announcement header paths.
        if (validation.downloadInitialBlockDownload()) return;
        synchronized (announcedBlocks) {
            if (announcedBlocks.putIfAbsent(block.hash(), Boolean.TRUE) != null) return;
            while (announcedBlocks.size() > MAX_RECENT_BLOCK_ANNOUNCEMENTS) {
                Iterator<Hash256> iterator = announcedBlocks.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
        for (Peer peer : peers.readyPeers()) {
            if (peer == source) continue;

            BlockAnnouncementState state = blockAnnouncements.get(peer);
            if (state != null && state.requestsHighBandwidthCompactBlocks) {
                long nonce = ThreadLocalRandom.current().nextLong();
                send(peer, BitcoinMessages.compactBlock(
                        CompactBlockFactory.create(block, nonce, CMPCTBLOCKS_VERSION), CMPCTBLOCKS_VERSION));
                continue;
            }
            if (state != null && state.prefersHeaders && state.bestHeaderSent != null) {
                Optional<List<ru.bitcoin.node.protocol.block.BlockHeader>> path =
                        validation.activeHeadersAfter(state.bestHeaderSent, block.hash(), MAX_BLOCKS_TO_ANNOUNCE);
                if (path.isPresent()) {
                    List<ru.bitcoin.node.protocol.block.BlockHeader> headers = path.orElseThrow();
                    if (!headers.isEmpty()) {
                        send(peer, BitcoinMessages.headers(new HeadersMessage(headers)));
                        state.bestHeaderSent = block.hash();
                    }
                    continue;
                }
            }

            // BIP130 fallback: if the peer did not request headers, we do not know a connecting
            // header for it, or the gap is larger than Core's announcement window, announce only
            // the current tip by INV and let normal headers synchronization recover the path.
            send(peer, BitcoinMessages.inv(new InvMessage(List.of(
                    new InventoryVector(InventoryVector.MSG_BLOCK, block.hash())))));
        }
    }

    public Hash256 submitTransaction(Transaction transaction) {
        validation.admit(transaction);
        validation.markMempoolUnbroadcast(transaction.txId());
        announceTransaction(transaction, null);
        reconsiderOrphanDescendants(transaction);
        return transaction.txId();
    }

    public record PackageSubmission(List<MempoolEntry> entries, List<Hash256> replacedTransactions) {
        public PackageSubmission {
            entries = List.copyOf(entries);
            replacedTransactions = List.copyOf(replacedTransactions);
        }
    }

    /** Atomically submits a child-with-parents package and relays every accepted member. */
    public PackageSubmission submitPackage(List<Transaction> transactions) {
        List<Transaction> packageTransactions = List.copyOf(transactions);
        Set<Hash256> before = validation.mempoolEntries().stream()
                .map(entry -> entry.transaction().txId()).collect(java.util.stream.Collectors.toSet());
        List<MempoolEntry> accepted = validation.admitPackage(packageTransactions);
        Set<Hash256> after = validation.mempoolEntries().stream()
                .map(entry -> entry.transaction().txId()).collect(java.util.stream.Collectors.toSet());
        List<Hash256> replaced = before.stream().filter(id -> !after.contains(id)).toList();
        for (var entry : accepted) {
            Transaction transaction = entry.transaction();
            validation.markMempoolUnbroadcast(transaction.txId());
            orphanage.remove(transaction);
            orphanage.removeConflicts(transaction);
            announceTransaction(transaction, null);
        }
        for (var entry : accepted) reconsiderOrphanDescendants(entry.transaction());
        return new PackageSubmission(accepted, replaced);
    }

    /** Relay only transactions still accepted after Core-style partial submission. */
    public Mempool.SubmissionResult submitPackage(List<Transaction> transactions, long clientMaxFeeRate) {
        var submission = validation.submitPackage(List.copyOf(transactions), clientMaxFeeRate);
        for (var entry : submission.entries()) {
            var transaction = entry.transaction();
            validation.markMempoolUnbroadcast(transaction.txId());
            orphanage.remove(transaction);
            orphanage.removeConflicts(transaction);
            announceTransaction(transaction, null);
        }
        for (var entry : submission.entries()) reconsiderOrphanDescendants(entry.transaction());
        return submission;
    }

    private void announceTransaction(Transaction transaction, Peer source) {
        if (source != null) {
            var state = txRelayStates.get(source);
            if (state != null) { state.markKnown(transaction.txId()); state.markKnown(transaction.wtxId()); }
        }
        inventoryBuckets.add(transaction.wtxId());
    }

    public Map<String, Object> inventoryBucketInfo() { return inventoryBuckets.info(); }

    private void processInventoryBacklog(long now, List<Peer> readyPeers) {
        if (!inventoryBuckets.ready(now)) return;
        var targets = readyPeers.stream().filter(peer -> peers.roleOf(peer).relaysTransactions())
                .filter(peer -> peer.remoteVersion().relay()).toList();
        var order = currentRelayMiningOrder();
        var batch = inventoryBuckets.drain(now, order,
                targets.stream().anyMatch(Peer::isInboundConnection),
                targets.stream().anyMatch(peer -> !peer.isInboundConnection()));
        for (var peer : targets) {
            var state = txRelayStates.get(peer);
            if (state == null) continue;
            for (var tx : peer.isInboundConnection() ? batch.inbound() : batch.outbound())
                state.queue(tx.txid(), tx.wtxid(), tx.feeRate());
        }
    }

    private List<TransactionRelayBuckets.Transaction> currentRelayMiningOrder() {
        return validation.mempoolMiningOrder().stream().map(entry ->
                new TransactionRelayBuckets.Transaction(entry.transaction().txId(), entry.transaction().wtxId(),
                        ru.bitcoin.node.mempool.FeeRate.fromFeeAndVSize(entry.fee(), entry.virtualSize()).satoshisPerKiloByte(),
                        Math.toIntExact(TransactionSerializer.serializedSize(entry.transaction(), true)))).toList();
    }

    private void maybeSendFeeFilters() {
        long now = System.nanoTime();
        long currentFilter = validation.feeFilterRate();
        long minimumRelay = validation.minimumRelayFeeRate();

        for (Peer peer : peers.readyPeers()) {
            if (peer.hasPermission(PeerPermission.FORCERELAY)) continue;
            // readyPeers() is a snapshot. The peer may be removed concurrently
            // before role lookup (for example when a header-sync race loser closes).
            if (!peers.hasRole(peer, ru.bitcoin.node.p2p.PeerConnectionRole.FULL_RELAY)) {
                continue;
            }

            VersionMessage version = peer.remoteVersion();
            if (version == null || version.version() < FEEFILTER_VERSION) {
                continue;
            }

            TxRelayState state = txRelayStates.get(peer);
            if (state == null) {
                continue;
            }

            long next = state.nextFeeFilterSendNanos();
            long sent = state.feeFilterSentSatPerKvB();

            if (next == 0L || now > next) {
                long filterToSend =
                        Math.max(
                                feeFilterRounder.round(currentFilter),
                                minimumRelay
                        );

                if (filterToSend != sent) {
                    send(
                            peer,
                            BitcoinMessages.feeFilter(filterToSend)
                    );
                    state.feeFilterSentSatPerKvB(filterToSend);
                }

                state.nextFeeFilterSendNanos(
                        saturatedAdd(
                                now,
                                randomExponentialDelayNanos(
                                        AVG_FEEFILTER_BROADCAST_INTERVAL_NANOS
                                )
                        )
                );
                continue;
            }

            /*
             * Core pulls a far-away broadcast forward when the local minimum
             * changes by more than roughly 25% down or 33% up.
             */
            boolean significantChange =
                    currentFilter < (3L * sent) / 4L
                            || currentFilter > (4L * sent) / 3L;

            if (significantChange
                    && saturatedAdd(now, MAX_FEEFILTER_CHANGE_DELAY_NANOS) < next) {
                state.nextFeeFilterSendNanos(
                        saturatedAdd(
                                now,
                                ThreadLocalRandom.current().nextLong(
                                        MAX_FEEFILTER_CHANGE_DELAY_NANOS
                                )
                        )
                );
            }
        }
    }

    private static long randomExponentialDelayNanos(long mean) {
        double u =
                Math.max(
                        ThreadLocalRandom.current().nextDouble(),
                        1.0e-12
                );
        double delay = -Math.log(u) * mean;
        if (delay >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) delay);
    }

    private void flushTransactionInventory() {
        long now = System.nanoTime();
        var readyPeers = peers.readyPeers();
        boolean ibd = validation.downloadInitialBlockDownload();
        if (!ibd) processInventoryBacklog(now, readyPeers);
        List<TransactionRelayBuckets.Transaction> miningOrder = null;
        for (Peer peer : readyPeers) {
            if (!peers.roleOf(peer).relaysTransactions()) {
                continue;
            }

            TxRelayState state = txRelayStates.get(peer);
            if (state == null) continue;
            boolean requested = state.mempoolRequested();
            if (!requested && (ibd || !peer.remoteVersion().relay() || state.pendingCount() == 0)) {
                continue;
            }

            long next = state.nextInventorySendNanos();
            if (next == 0L && !requested && !peer.hasPermission(PeerPermission.NOBAN)) {
                state.nextInventorySendNanos(
                        saturatedAdd(
                                now,
                                nextInventoryDelayNanos(peer)
                        )
                );
                continue;
            }

            if (next != 0L && now < next && !peer.hasPermission(PeerPermission.NOBAN)) {
                continue;
            }

            state.nextInventorySendNanos(
                    saturatedAdd(
                            now,
                            nextInventoryDelayNanos(peer)
                    )
            );

            if (requested) {
                var snapshot = validation.mempoolEntries();
                state.completeMempoolRequest();
                var inventory = new ArrayList<InventoryVector>();
                for (var entry : snapshot) {
                    if (!MempoolInventory.passesFeeFilter(entry, state.feeFilterSatPerKvB())) continue;
                    var tx = entry.transaction();
                    inventory.add(new InventoryVector(peer.remoteWtxidRelay() ? MSG_WTX : InventoryVector.MSG_TX,
                            peer.remoteWtxidRelay() ? tx.wtxId() : tx.txId()));
                    state.markKnown(tx.txId());
                    state.markKnown(tx.wtxId());
                    if (inventory.size() == InvMessage.MAX_INVENTORY_SIZE) {
                        send(peer, BitcoinMessages.inv(new InvMessage(List.copyOf(inventory))));
                        inventory.clear();
                    }
                }
                if (!inventory.isEmpty()) send(peer, BitcoinMessages.inv(new InvMessage(List.copyOf(inventory))));
                continue;
            }

            int broadcastMaximum = InvMessage.MAX_INVENTORY_SIZE;
            if (miningOrder == null) miningOrder = currentRelayMiningOrder();
            state.refreshPending(miningOrder);

            List<TxRelayState.PendingAnnouncement> selected =
                    state.takeForRelay(
                            peer.remoteWtxidRelay(),
                            broadcastMaximum
                    );

            if (selected.isEmpty()) {
                continue;
            }

            List<InventoryVector> inventory =
                    new ArrayList<>(selected.size());

            for (TxRelayState.PendingAnnouncement announcement : selected) {
                Hash256 hash =
                        peer.remoteWtxidRelay()
                                ? announcement.wtxId()
                                : announcement.txId();

                inventory.add(
                        new InventoryVector(
                                peer.remoteWtxidRelay()
                                        ? MSG_WTX
                                        : InventoryVector.MSG_TX,
                                hash
                        )
                );

                /*
                 * Known inventory is updated when the announcement is actually
                 * selected for transmission, not merely when it is queued.
                 */
                state.markKnown(announcement.txId());
                state.markKnown(announcement.wtxId());
            }

            send(
                    peer,
                    BitcoinMessages.inv(
                            new InvMessage(
                                    List.copyOf(inventory)
                            )
                    )
            );
        }
    }

    private static long nextInventoryDelayNanos(Peer peer) {
        long mean =
                peer.isInboundConnection()
                        ? INBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS
                        : OUTBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS;

        /*
         * Core uses exponentially distributed delays. Clamp u away from zero
         * so the logarithm remains finite.
         */
        double u =
                Math.max(
                        ThreadLocalRandom.current().nextDouble(),
                        1.0e-12
                );

        double delay =
                -Math.log(u) * mean;

        if (delay >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }

        return Math.max(
                1L,
                (long) delay
        );
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void broadcast(BitcoinMessage message, Peer source) {
        for (Peer peer : peers.readyPeers()) if (peer != source) send(peer, message);
    }

    /**
     * Queue ordinary outbound traffic on the same per-peer serial worker as GETDATA responses.
     */
    private void send(Peer peer, BitcoinMessage message) {
        if (closed) return;
        PeerOutbound sender = outbound.get(peer);
        if (sender == null || !sender.execute(() -> sendDirect(peer, message), 128L + message.payloadLength())) {
            disconnect(peer);
        }
    }

    /**
     * Must only be called by the peer's PeerOutbound worker.
     */
    private void sendDirect(Peer peer, BitcoinMessage message) throws IOException {
        if (closed) return;
        var timeout = sendTimeouts.schedule(() -> disconnect(peer), 10, TimeUnit.SECONDS);
        try {
            peer.send(message);
        } finally {
            timeout.cancel(false);
        }
    }

    private void disconnect(Peer peer) {
        try {
            peer.close();
        } catch (IOException exception) {
            log.debug("Peer close failed", exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        peers.removePeerListener(connections);
        attached.forEach(peer -> peer.removeMessageListener(messages));

        requestTimer.shutdownNow();
        inbound.values().forEach(PeerInbound::stop);
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                attached.forEach(this::disconnect);
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Relay worker did not stop");
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }

        outbound.values().forEach(PeerOutbound::shutdownNow);
        attached.clear();
        inbound.clear();
        outbound.clear();
        blockAnnouncements.clear();
        mostRecentBlock = null;
        txRelayStates.clear();
        validatedPeerHeaders.clear();
        inventoryBuckets.clear();
        pendingCompactBlocks.clear();
        synchronized (compactFallbacks) { compactFallbacks.clear(); }
        synchronized (highBandwidthCompactPeers) {
            highBandwidthCompactPeers.clear();
        }
        sendTimeouts.shutdownNow();
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    /**
     * One bounded serial send/work queue per peer.
     */
    private final class PeerOutbound {
        private final ThreadPoolExecutor executor;
        private final Peer peer;
        private final AtomicLong retainedBytes = new AtomicLong();

        private PeerOutbound(Peer peer) {
            this.peer = peer;
            executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(MAX_OUTBOUND_TASKS_PER_PEER),
                    Thread.ofPlatform().daemon().name("bitcoin-relay-peer-", 0).factory());
        }

        private synchronized boolean execute(IoTask task, long bytes) {
            if (closed || executor.isShutdown()) return false;
            if (bytes > MAX_OUTBOUND_BYTES_PER_PEER - retainedBytes.get()) return false;
            while (true) {
                long total = outboundBytes.get();
                if (bytes > MAX_OUTBOUND_BYTES - total) return false;
                if (outboundBytes.compareAndSet(total, total + bytes)) break;
            }
            retainedBytes.addAndGet(bytes);
            var reserved = new ReservedTask(task, bytes);
            try {
                executor.execute(reserved);
                return true;
            } catch (RejectedExecutionException exception) {
                reserved.release();
                return false;
            }
        }

        private final class ReservedTask implements Runnable {
            private final IoTask task;
            private final long bytes;
            private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

            private ReservedTask(IoTask task, long bytes) {
                this.task = task;
                this.bytes = bytes;
            }

            @Override
            public void run() {
                boolean successful = false;
                try {
                    if (!closed && peer.isReady()) task.run();
                    successful = true;
                } catch (IOException | IllegalStateException exception) {
                    disconnectPeer(exception);
                } catch (RuntimeException exception) {
                    log.error("Unable to serve outbound peer work", exception);
                    disconnectPeer(exception);
                } finally {
                    boolean continued = false;
                    if (successful && task instanceof GetDataWork work && !work.complete()) {
                        synchronized (PeerOutbound.this) {
                            if (!closed && peer.isReady() && !executor.isShutdown()) {
                                try {
                                    executor.execute(this);
                                    continued = true;
                                } catch (RejectedExecutionException exception) {
                                    disconnectPeer(exception);
                                }
                            }
                        }
                    }
                    if (!continued) release();
                }
            }

            private void release() {
                if (released.compareAndSet(false, true)) {
                    retainedBytes.addAndGet(-bytes);
                    outboundBytes.addAndGet(-bytes);
                }
            }
        }

        private void disconnectPeer(Exception cause) {
            log.debug("Outbound relay failed", cause);
            disconnect(peer);
        }

        private synchronized void shutdownNow() {
            for (Runnable abandoned : executor.shutdownNow()) ((ReservedTask) abandoned).release();
        }
    }
}
