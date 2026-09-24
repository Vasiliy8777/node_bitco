package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.MempoolAdmissionException;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
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
    private final AtomicLong outboundBytes = new AtomicLong();

    private final NodeValidationService validation;
    private final NodeSyncInfrastructure sync;
    private final PeerManager peers;
    private final Set<Peer> attached = ConcurrentHashMap.newKeySet();
    private final Map<Peer, PeerOutbound> outbound = new ConcurrentHashMap<>();
    private final Map<Peer, BlockAnnouncementState> blockAnnouncements = new ConcurrentHashMap<>();
    private final Map<Peer, LinkedHashMap<Hash256, PendingCompactBlock>> pendingCompactBlocks = new ConcurrentHashMap<>();
    private final Deque<Peer> highBandwidthCompactPeers = new ArrayDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final ScheduledExecutorService sendTimeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-relay-send-timeout").factory());
    private final ScheduledExecutorService requestTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-tx-request-timer").factory());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("bitcoin-relay").factory());
    private final TransactionRequestScheduler transactionRequests = new TransactionRequestScheduler();
    private final Map<Hash256, Orphan> orphans = new LinkedHashMap<>();
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
    private static final int MAX_PENDING_COMPACT_BLOCKS_PER_PEER = 16;
    private static final long COMPACT_BLOCK_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private final PeerMessageListener messages = this::enqueue;
    private final Consumer<Peer> connections = this::attach;
    private volatile boolean closed;

    private record Orphan(Transaction transaction, long expires) {
    }

    private record PendingCompactBlock(CompactBlockReconstruction.Partial partial, long expires) {
    }

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
        this.validation = Objects.requireNonNull(validation, "validation");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.peers = Objects.requireNonNull(peers, "peers");
        peers.addPeerListener(connections);
        requestTimer.scheduleWithFixedDelay(this::enqueueRequestTick, 1, 1, TimeUnit.SECONDS);
    }

    private void enqueueRequestTick() {
        if (closed) return;
        try {
            worker.execute(() -> {
                if (!closed) {
                    dispatchTransactionRequests();
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
            pendingCompactBlocks.computeIfAbsent(peer, ignored -> new LinkedHashMap<>());
            peer.addMessageListener(messages);

            peer.addCloseListener((source, cause) -> {
                source.removeMessageListener(messages);
                attached.remove(source);
                PeerOutbound sender = outbound.remove(source);
                blockAnnouncements.remove(source);
                pendingCompactBlocks.remove(source);
                removeHighBandwidthCompactPeer(source);
                if (sender != null) sender.shutdownNow();
            });
        }
    }

    private void enqueue(Peer peer, BitcoinMessage message) {
        if (closed || !Set.of("inv", "tx", "getdata", "getheaders", "notfound", "sendheaders", "sendcmpct", "cmpctblock", "getblocktxn", "blocktxn").contains(message.command()))
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
                    disconnect(peer);
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
                if (role.relaysTransactions()) requestTransactions(peer, BitcoinMessages.decodeInv(message));
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
            case "notfound" -> {
                for (var vector : BitcoinMessages.decodeNotFound(message).inventory()) {
                    transactionRequests.notFound(peer, vector.hash());
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
        if (validation.findBlock(hash).isPresent()) return;

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

        LinkedHashMap<Hash256, PendingCompactBlock> byHash = pendingCompactBlocks.get(peer);
        if (byHash == null) return;
        synchronized (byHash) {
            byHash.put(hash, new PendingCompactBlock(partial,
                    System.nanoTime() + COMPACT_BLOCK_TIMEOUT_NANOS));
            while (byHash.size() > MAX_PENDING_COMPACT_BLOCKS_PER_PEER) {
                Iterator<Hash256> iterator = byHash.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
        send(peer, BitcoinMessages.getBlockTxn(new BlockTransactionsRequest(hash, partial.missingIndexes())));
    }

    private void receiveBlockTransactions(Peer peer, BlockTransactionsMessage response) {
        LinkedHashMap<Hash256, PendingCompactBlock> byHash = pendingCompactBlocks.get(peer);
        if (byHash == null) return;
        PendingCompactBlock pending;
        synchronized (byHash) {
            pending = byHash.remove(response.blockHash());
        }
        if (pending == null) return;
        if (pending.expires() < System.nanoTime()) {
            requestFullBlock(peer, response.blockHash());
            return;
        }
        try {
            processReconstructedBlock(peer, pending.partial().fill(response.transactions()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // Reconstruction failure is not enough to blame the peer: mempool contents may have
            // changed or short IDs may have collided. Fall back to an ordinary witness block.
            requestFullBlock(peer, response.blockHash());
        }
    }

    private void processReconstructedBlock(Peer source, Block block) {
        BlockProcessingResult result = validation.processBlock(block);
        if (result == BlockProcessingResult.CONNECTED) {
            promoteHighBandwidthCompactPeer(source);
            relayConnectedBlock(block, source);
        }
    }

    private void requestFullBlock(Peer peer, Hash256 hash) {
        send(peer, BitcoinMessages.getData(new GetDataMessage(List.of(
                new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, hash)))));
    }

    private void expireCompactBlocks() {
        long now = System.nanoTime();
        for (var entry : pendingCompactBlocks.entrySet()) {
            LinkedHashMap<Hash256, PendingCompactBlock> byHash = entry.getValue();
            List<Hash256> expired = new ArrayList<>();
            synchronized (byHash) {
                Iterator<Map.Entry<Hash256, PendingCompactBlock>> iterator = byHash.entrySet().iterator();
                while (iterator.hasNext()) {
                    var pending = iterator.next();
                    if (pending.getValue().expires() <= now) {
                        expired.add(pending.getKey());
                        iterator.remove();
                    }
                }
            }
            for (Hash256 hash : expired) requestFullBlock(entry.getKey(), hash);
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
        orphans.values().removeIf(orphan -> orphan.expires() < System.nanoTime());
        if (!transactionRequests.isExpected(peer, transaction.txId(), transaction.wtxId())) return;
        transactionRequests.forget(transaction.txId(), transaction.wtxId());
        try {
            validation.admit(transaction);
            announceTransaction(transaction, peer);
            boolean progress;
            do {
                progress = false;
                for (var iterator = orphans.values().iterator(); iterator.hasNext(); ) {
                    var orphan = iterator.next().transaction();
                    try {
                        validation.admit(orphan);
                        iterator.remove();
                        announceTransaction(orphan, null);
                        progress = true;
                    } catch (MempoolAdmissionException exception) {
                        if (!exception.getMessage().startsWith("Missing UTXO:")) iterator.remove();
                    } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                             | ru.bitcoin.node.script.ScriptExecutionException |
                             ru.bitcoin.node.script.ScriptParseException exception) {
                        iterator.remove();
                    }
                }
            } while (progress);
        } catch (MempoolAdmissionException exception) {
            if (exception.getMessage().startsWith("Missing UTXO:") && orphans.size() < 100
                    && TransactionSerializer.serialize(transaction).length <= 100_000) {
                orphans.put(transaction.txId(), new Orphan(transaction, System.nanoTime() + Duration.ofMinutes(2).toNanos()));
                requestTransactions(peer, new InvMessage(transaction.inputs().stream()
                        .map(input -> new InventoryVector(InventoryVector.MSG_TX, input.previousOutput().transactionId())).distinct().toList()));
            }
        } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                 | ru.bitcoin.node.script.ScriptExecutionException |
                 ru.bitcoin.node.script.ScriptParseException exception) {
            log.debug("Rejected invalid transaction {}", transaction.txId());
        }
    }

    /**
     * GETDATA is valid up to the protocol inventory limit. Keep the complete request as one
     * bounded per-peer task instead of expanding it into tens of thousands of queued send tasks.
     */
    private void queueGetData(Peer peer, GetDataMessage request) {
        PeerOutbound sender = outbound.get(peer);
        // Charge decoded vectors/hash arrays/list references as well as the task itself.
        long retainedBytes = 256L + 128L * request.inventory().size();
        if (sender == null || !sender.execute(() -> serveData(peer, request), retainedBytes)) {
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
    private void serveData(Peer peer, GetDataMessage request) throws IOException {
        PeerConnectionRole role = peers.roleOf(peer);
        if (request.inventory().size() > GetDataMessage.MAX_INVENTORY_SIZE) {
            throw new IllegalArgumentException("getdata exceeds protocol inventory limit");
        }

        Map<Hash256, Transaction> byTxId = new HashMap<>();
        Map<Hash256, Transaction> byWtxId = new HashMap<>();
        for (var entry : validation.mempoolEntries()) {
            Transaction transaction = entry.transaction();
            byTxId.put(transaction.txId(), transaction);
            byWtxId.put(transaction.wtxId(), transaction);
        }

        List<InventoryVector> missing = new ArrayList<>();
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

        if (!missing.isEmpty()) {
            sendDirect(peer, BitcoinMessages.notFound(new NotFoundMessage(missing)));
        }
    }

    public BlockProcessingResult submitBlock(Block block) {
        var result = validation.processBlock(block);
        if (result == BlockProcessingResult.CONNECTED) {
            sync.headerSyncService().process(new HeadersMessage(List.of(block.header())));
            relayConnectedBlock(block, null);
        }
        return result;
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
        return transaction.txId();
    }

    private void announceTransaction(Transaction transaction, Peer source) {
        for (Peer peer : peers.readyPeers()) {
            if (peer == source || !peers.roleOf(peer).relaysTransactions() || !peer.remoteVersion().relay()) continue;
            send(peer, BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(
                    peer.remoteWtxidRelay() ? MSG_WTX : InventoryVector.MSG_TX,
                    peer.remoteWtxidRelay() ? transaction.wtxId() : transaction.txId())))));
        }
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
        pendingCompactBlocks.clear();
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
                try {
                    if (!closed && peer.isReady()) task.run();
                } catch (IOException | IllegalStateException exception) {
                    disconnectPeer(exception);
                } catch (RuntimeException exception) {
                    log.error("Unable to serve outbound peer work", exception);
                    disconnectPeer(exception);
                } finally {
                    release();
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
