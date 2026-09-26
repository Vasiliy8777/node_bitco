package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.MempoolAdmissionException;
import ru.bitcoin.node.mempool.MempoolEntry;
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
import java.util.function.Consumer;

/**
 * Bounded unsolicited-message processing and relay. No disk work on peer reader threads.
 */
public final class NodeRelayService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NodeRelayService.class);
    private static final long MSG_WTX = 5;
    private static final long MAX_QUEUED_INBOUND_BYTES = 16_000_000L;
    private static final int MAX_OUTBOUND_TASKS_PER_PEER = 64;
    private static final long MAX_OUTBOUND_BYTES_PER_PEER = 16_000_000L;
    private static final long MAX_OUTBOUND_BYTES = 64_000_000L;

    // Bitcoin Core v31.1 transaction-inventory trickle constants.
    static final long INBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS =
            Duration.ofSeconds(5).toNanos();
    static final long OUTBOUND_INVENTORY_BROADCAST_INTERVAL_NANOS =
            Duration.ofSeconds(2).toNanos();
    static final int INVENTORY_BROADCAST_PER_SECOND = 14;
    static final int INVENTORY_BROADCAST_TARGET =
            INVENTORY_BROADCAST_PER_SECOND * 5;
    static final int INVENTORY_BROADCAST_MAX = 1_000;

    // BIP133 / Bitcoin Core v31.1.
    static final int FEEFILTER_VERSION = 70_013;
    static final long AVG_FEEFILTER_BROADCAST_INTERVAL_NANOS =
            Duration.ofMinutes(10).toNanos();
    static final long MAX_FEEFILTER_CHANGE_DELAY_NANOS =
            Duration.ofMinutes(5).toNanos();

    private final AtomicLong outboundBytes = new AtomicLong();

    private final NodeValidationService validation;
    private final NodeSyncInfrastructure sync;
    private final PeerManager peers;
    private final BlockDownloadScheduler blockDownloadScheduler;
    private final Set<Peer> attached = ConcurrentHashMap.newKeySet();
    private final Map<Peer, PeerOutbound> outbound = new ConcurrentHashMap<>();
    private final Map<Peer, BlockAnnouncementState> blockAnnouncements = new ConcurrentHashMap<>();
    private final Map<Peer, TxRelayState> txRelayStates = new ConcurrentHashMap<>();
    private final PendingCompactBlocks pendingCompactBlocks = new PendingCompactBlocks();
    // Guarded by the map monitor. These are relay-owned fallbacks only; a hash already
    // owned by BlockDownloadScheduler must stay in the scheduler's single download lifecycle.
    private final Map<Peer, Map<Hash256, Long>> compactFallbacks = new HashMap<>();
    private static final int MAX_COMPACT_FALLBACKS = 256;
    private static final int MAX_COMPACT_FALLBACKS_PER_PEER = 16;
    private final Deque<Peer> highBandwidthCompactPeers = new ArrayDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final ScheduledExecutorService sendTimeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-relay-send-timeout").factory());
    private final ScheduledExecutorService requestTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-tx-request-timer").factory());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("bitcoin-relay").factory());
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
    private static final int MAX_CMPCTBLOCK_DEPTH = 5;
    private static final int MAX_BLOCKTXN_DEPTH = 10;
    private static final long CMPCTBLOCKS_VERSION = 2;
    // BIP152/Core keeps at most three peers in high-bandwidth announcement mode.
    private static final int MAX_HIGH_BANDWIDTH_COMPACT_PEERS = 3;
    private static final long COMPACT_BLOCK_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private final PeerMessageListener messages = this::enqueue;
    private final Consumer<Peer> connections = this::attach;
    private volatile boolean closed;


    /**
     * Per-peer BIP130 block-announcement preference and last header sent.
     */
    private static final class BlockAnnouncementState {
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
        this.validation = Objects.requireNonNull(validation, "validation");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.blockDownloadScheduler = blockDownloadScheduler;
        peers.addPeerListener(connections);
        requestTimer.scheduleWithFixedDelay(this::enqueueRequestTick, 1, 1, TimeUnit.SECONDS);
    }

    private void enqueueRequestTick() {
        if (closed) return;
        try {
            worker.execute(() -> {
                if (!closed) {
                    dispatchTransactionRequests();
                    flushTransactionInventory();
                    maybeSendFeeFilters();
                    expireCompactBlocks();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Service is closing or inbound work is temporarily saturated.
        }
    }

    private void attach(Peer peer) {
        if (!closed && attached.add(peer)) {
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
                pendingCompactBlocks.removePeer(source);
                synchronized (compactFallbacks) { compactFallbacks.remove(source); }
                removeHighBandwidthCompactPeer(source);
                orphanage.removePeer(source);
                if (sender != null) sender.shutdownNow();
            });
        }
    }

    private void enqueue(Peer peer, BitcoinMessage message) {
        if ("block".equals(message.command())) {
            synchronized (compactFallbacks) {
                if (!compactFallbacks.containsKey(peer)) return;
            }
        }
        if (closed || !Set.of("inv", "tx", "getdata", "getheaders", "notfound", "sendheaders", "sendcmpct", "feefilter", "cmpctblock", "getblocktxn", "blocktxn", "block").contains(message.command()))
            return;
        long bytes = message.payloadLength();
        if (queuedBytes.addAndGet(bytes) > MAX_QUEUED_INBOUND_BYTES) {
            queuedBytes.addAndGet(-bytes);
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    if (!closed && peer.isReady()) handle(peer, message);
                } catch (IOException | IllegalArgumentException exception) {
                    log.debug("Rejected peer message {}", message.command(), exception);
                    peer.disconnectForProtocolViolation(
                            "Rejected " + message.command() + " message", exception);
                } catch (RuntimeException exception) {
                    log.error("Unable to process peer message {}", message.command(), exception);
                } finally {
                    queuedBytes.addAndGet(-bytes);
                }
            });
        } catch (RejectedExecutionException exception) {
            queuedBytes.addAndGet(-bytes);
        }
    }

    private void handle(Peer peer, BitcoinMessage message) throws IOException {
        expireCompactBlocks();
        PeerConnectionRole role = peers.roleOf(peer);
        switch (message.command()) {
            case "inv" -> {
                if (role.relaysTransactions()) {
                    InvMessage inventory = BitcoinMessages.decodeInv(message);
                    TxRelayState relayState = txRelayStates.get(peer);
                    if (relayState != null) {
                        for (InventoryVector vector : inventory.inventory()) {
                            if (vector.type() == InventoryVector.MSG_TX
                                    || vector.type() == InventoryVector.MSG_WITNESS_TX
                                    || vector.type() == MSG_WTX) {
                                relayState.markKnown(vector.hash());
                            }
                        }
                    }
                    requestTransactions(peer, inventory);
                }
            }
            case "tx" -> {
                if (role.relaysTransactions()) receiveTransaction(peer, TransactionParser.parse(message.payload()));
            }
            case "getdata" -> queueGetData(peer, BitcoinMessages.decodeGetData(message));
            case "getheaders" -> {
                var request = GetHeadersMessageCodec.decode(message.payload());
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
            case "cmpctblock" ->
                    receiveCompactBlock(peer, BitcoinMessages.decodeCompactBlock(message, CMPCTBLOCKS_VERSION));
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

    private void receiveCompactBlock(Peer peer, CompactBlockMessage compact) {
        Hash256 hash = compact.header().hash();
        synchronized (compactFallbacks) {
            var requests = compactFallbacks.get(peer);
            if (requests != null && requests.containsKey(hash)) return;
        }
        if (pendingCompactBlocks.contains(peer, hash) || validation.findBlock(hash).isPresent()) return;

        // A compact block carries a real block header. Feed it through the existing header
        // validation/index pipeline before accepting any reconstructed body.
        sync.headerSyncService().process(new HeadersMessage(List.of(compact.header())));

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

        BlockProcessingResult result;
        try {
            result = validation.processBlock(block);
        } catch (ru.bitcoin.node.consensus.block.BlockValidationException exception) {
            // Both complete and partially reconstructed bodies can contain a short-ID collision.
            // Request authoritative full data; never treat storage/runtime failures as collisions.
            requestFullBlock(source, block.hash());
            return;
        }
        if (result == BlockProcessingResult.CONNECTED) {
            promoteHighBandwidthCompactPeer(source);
            onConnectedBlock(block, source);
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
        if (validation.processBlock(block) == BlockProcessingResult.CONNECTED) {
            promoteHighBandwidthCompactPeer(peer);
            onConnectedBlock(block, peer);
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
        if (!transactionRequests.isExpected(peer, transaction.txId(), transaction.wtxId())) return;
        transactionRequests.forget(transaction.txId(), transaction.wtxId());
        try {
            validation.admit(transaction);
            announceTransaction(transaction, peer);
            reconsiderOrphanDescendants(transaction);
        } catch (MempoolAdmissionException exception) {
            if (isMissingInput(exception) && orphanage.add(transaction, peer)) {
                requestTransactions(peer, new InvMessage(transaction.inputs().stream()
                        .map(input -> new InventoryVector(InventoryVector.MSG_TX, input.previousOutput().transactionId()))
                        .distinct().toList()));
            }
        } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                 | ru.bitcoin.node.script.ScriptExecutionException
                 | ru.bitcoin.node.script.ScriptParseException exception) {
            log.debug("Rejected invalid transaction {}", transaction.txId());
        }
    }

    private void reconsiderOrphanDescendants(Transaction acceptedParent) {
        ArrayDeque<Hash256> acceptedParents = new ArrayDeque<>();
        acceptedParents.add(acceptedParent.txId());
        while (!acceptedParents.isEmpty()) {
            Hash256 parentTxid = acceptedParents.removeFirst();
            for (Transaction orphan : orphanage.childrenOf(parentTxid)) {
                try {
                    validation.admit(orphan);
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
        PeerOutbound sender = outbound.get(peer);
        long retainedBytes = 256L + 8L * request.indexes().size();
        if (sender == null || !sender.execute(() -> serveBlockTransactions(peer, request), retainedBytes)) {
            disconnect(peer);
        }
    }

    private void serveBlockTransactions(Peer peer, BlockTransactionsRequest request) throws IOException {
        OptionalLong depth = validation.activeBlockDepth(request.blockHash());
        Optional<Block> blockOptional = validation.findBlock(request.blockHash());
        if (depth.isEmpty() || blockOptional.isEmpty()) return;
        Block block = blockOptional.orElseThrow();

        if (depth.getAsLong() > MAX_BLOCKTXN_DEPTH) {
            sendDirect(peer, new BitcoinMessage("block", BlockSerializer.serialize(block)));
            return;
        }

        List<Transaction> transactions = new ArrayList<>(request.indexes().size());
        for (int index : request.indexes()) {
            if (index < 0 || index >= block.transactions().size()) {
                throw new IllegalArgumentException("getblocktxn transaction index out of bounds: " + index);
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
        boolean needsTransactions = role.relaysTransactions() && request.inventory().stream().anyMatch(vector ->
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

            if (vector.type() == InventoryVector.MSG_CMPCT_BLOCK) {
                var block = validation.findBlock(vector.hash());
                OptionalLong depth = validation.activeBlockDepth(vector.hash());
                if (block.isPresent() && depth.isPresent()) {
                    if (depth.getAsLong() <= MAX_CMPCTBLOCK_DEPTH) {
                        long nonce = ThreadLocalRandom.current().nextLong();
                        sendDirect(peer, BitcoinMessages.compactBlock(
                                CompactBlockFactory.create(block.orElseThrow(), nonce, CMPCTBLOCKS_VERSION),
                                CMPCTBLOCKS_VERSION));
                    } else {
                        sendDirect(peer, new BitcoinMessage("block", BlockSerializer.serialize(block.orElseThrow())));
                    }
                    continue;
                }
            } else if (vector.type() == InventoryVector.MSG_BLOCK || vector.type() == InventoryVector.MSG_WITNESS_BLOCK) {
                var block = validation.findBlock(vector.hash());
                if (block.isPresent()) {
                    sendDirect(peer, new BitcoinMessage("block", vector.type() == InventoryVector.MSG_BLOCK
                            ? BlockSerializer.serializeLegacy(block.get()) : BlockSerializer.serialize(block.get())));
                    continue;
                }
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
            }
            missing.add(vector);
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
                        || type == InventoryVector.MSG_CMPCT_BLOCK) break;
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
            sync.headerSyncService().process(new HeadersMessage(List.of(block.header())));
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
        handleConnectedBlockOrphans(block);
        relayConnectedBlock(block, source);
    }

    /**
     * Announces an active-tip block once, excluding the peer that supplied its body.
     */
    public void relayConnectedBlock(Block block, Peer source) {
        Objects.requireNonNull(block, "block");
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
        announceTransaction(transaction, null);
        reconsiderOrphanDescendants(transaction);
        return transaction.txId();
    }

    private void announceTransaction(Transaction transaction, Peer source) {
        MempoolEntry entry = null;
        for (MempoolEntry candidate : validation.mempoolEntries()) {
            if (candidate.transaction().txId().equals(transaction.txId())) {
                entry = candidate;
                break;
            }
        }

        long feeRate =
                entry == null
                        ? 0L
                        : entry.feeRate().satoshisPerKiloByte();

        for (Peer peer : peers.readyPeers()) {
            if (peer == source
                    || !peers.roleOf(peer).relaysTransactions()
                    || !peer.remoteVersion().relay()) {
                continue;
            }

            TxRelayState relayState = txRelayStates.get(peer);
            if (relayState == null) {
                continue;
            }

            Hash256 announcedHash =
                    peer.remoteWtxidRelay()
                            ? transaction.wtxId()
                            : transaction.txId();

            /*
             * Queue rather than immediately send. Core keeps transaction
             * inventory pending per peer and releases it only on the peer's
             * randomized inventory trickle schedule.
             */
            if (feeRate < relayState.feeFilterSatPerKvB()
                    || relayState.knows(announcedHash)) {
                continue;
            }

            relayState.queue(
                    transaction.txId(),
                    transaction.wtxId(),
                    feeRate
            );
        }
    }

    private void maybeSendFeeFilters() {
        long now = System.nanoTime();
        long currentFilter = validation.feeFilterRate();
        long minimumRelay = validation.minimumRelayFeeRate();

        for (Peer peer : peers.readyPeers()) {
            if (!peers.roleOf(peer).relaysTransactions()) {
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

        for (Peer peer : peers.readyPeers()) {
            if (!peers.roleOf(peer).relaysTransactions()
                    || !peer.remoteVersion().relay()) {
                continue;
            }

            TxRelayState state = txRelayStates.get(peer);
            if (state == null || state.pendingCount() == 0) {
                continue;
            }

            long next = state.nextInventorySendNanos();
            if (next == 0L) {
                state.nextInventorySendNanos(
                        saturatedAdd(
                                now,
                                nextInventoryDelayNanos(peer)
                        )
                );
                continue;
            }

            if (now < next) {
                continue;
            }

            state.nextInventorySendNanos(
                    saturatedAdd(
                            now,
                            nextInventoryDelayNanos(peer)
                    )
            );

            int backlog = state.pendingCount();

            /*
             * Bitcoin Core v31.1:
             * target = 14 * 5 = 70;
             * add 5 for each 1000 queued items;
             * hard-cap a single trickle transmission at 1000 tx inventory.
             */
            int broadcastMaximum =
                    Math.min(
                            INVENTORY_BROADCAST_MAX,
                            INVENTORY_BROADCAST_TARGET
                                    + (backlog / 1_000) * 5
                    );

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
        outbound.clear();
        blockAnnouncements.clear();
        txRelayStates.clear();
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
