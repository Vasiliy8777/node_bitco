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

/** Bounded unsolicited-message processing and relay. No disk work on peer reader threads. */
public final class NodeRelayService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NodeRelayService.class);
    private static final long MSG_WTX = 5;
    private static final long MAX_QUEUED_INBOUND_BYTES = 16_000_000L;
    private static final int MAX_OUTBOUND_TASKS_PER_PEER = 64;

    private final NodeValidationService validation;
    private final NodeSyncInfrastructure sync;
    private final PeerManager peers;
    private final Set<Peer> attached = ConcurrentHashMap.newKeySet();
    private final Map<Peer, PeerOutbound> outbound = new ConcurrentHashMap<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final ScheduledExecutorService sendTimeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("bitcoin-relay-send-timeout").factory());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("bitcoin-relay").factory());
    private final Map<Hash256, Request> requested = new HashMap<>();
    private final Map<Hash256, Orphan> orphans = new LinkedHashMap<>();
    private final PeerMessageListener messages = this::enqueue;
    private final Consumer<Peer> connections = this::attach;
    private volatile boolean closed;

    private record Request(Peer peer, long expires, boolean witnessId) { }
    private record Orphan(Transaction transaction, long expires) { }

    public NodeRelayService(NodeValidationService validation, NodeSyncInfrastructure sync, PeerManager peers) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.peers = Objects.requireNonNull(peers, "peers");
        peers.addPeerListener(connections);
    }

    private void attach(Peer peer) {
        if (!closed && attached.add(peer)) {
            outbound.computeIfAbsent(peer, PeerOutbound::new);
            peer.addMessageListener(messages);
            peer.addCloseListener((source, cause) -> {
                source.removeMessageListener(messages);
                attached.remove(source);
                PeerOutbound sender = outbound.remove(source);
                if (sender != null) sender.shutdownNow();
            });
        }
    }

    private void enqueue(Peer peer, BitcoinMessage message) {
        if (closed || !Set.of("inv", "tx", "getdata", "getheaders", "notfound").contains(message.command())) return;
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
        switch (message.command()) {
            case "inv" -> requestTransactions(peer, BitcoinMessages.decodeInv(message));
            case "tx" -> receiveTransaction(peer, TransactionParser.parse(message.payload()));
            case "getdata" -> queueGetData(peer, BitcoinMessages.decodeGetData(message));
            case "getheaders" -> {
                var request = GetHeadersMessageCodec.decode(message.payload());
                send(peer, BitcoinMessages.headers(new HeadersMessage(validation.headers(request.locatorHashes(), request.stopHash()))));
            }
            case "notfound" -> {
                for (var vector : BitcoinMessages.decodeNotFound(message).inventory()) {
                    var request = requested.get(vector.hash());
                    if (request != null && request.peer() == peer) requested.remove(vector.hash());
                }
            }
            default -> { }
        }
    }

    private void requestTransactions(Peer peer, InvMessage inventory) throws IOException {
        long now = System.nanoTime();
        requested.values().removeIf(request -> request.expires() < now || !request.peer().isReady());
        Set<Hash256> known = new HashSet<>();
        for (var entry : validation.mempoolEntries()) {
            known.add(entry.transaction().txId());
            known.add(entry.transaction().wtxId());
        }
        List<InventoryVector> needed = new ArrayList<>();
        for (var vector : inventory.inventory()) {
            if (vector.type() != InventoryVector.MSG_TX && vector.type() != MSG_WTX) continue;
            if (known.contains(vector.hash()) || requested.containsKey(vector.hash())) continue;
            if (requested.size() >= 1024 || needed.size() >= 128) break;
            requested.put(vector.hash(), new Request(peer, now + Duration.ofSeconds(30).toNanos(), vector.type() == MSG_WTX));
            needed.add(new InventoryVector(vector.type() == MSG_WTX ? MSG_WTX : InventoryVector.MSG_WITNESS_TX, vector.hash()));
        }
        if (!needed.isEmpty()) send(peer, BitcoinMessages.getData(new GetDataMessage(needed)));
    }

    private void receiveTransaction(Peer peer, Transaction transaction) throws IOException {
        orphans.values().removeIf(orphan -> orphan.expires() < System.nanoTime());
        var byId = requested.get(transaction.txId());
        var byWitness = requested.get(transaction.wtxId());
        if ((byId == null || byId.peer() != peer) && (byWitness == null || byWitness.peer() != peer)) return;
        requested.remove(transaction.txId());
        requested.remove(transaction.wtxId());
        try {
            validation.admit(transaction);
            announceTransaction(transaction, peer);
            boolean progress;
            do {
                progress = false;
                for (var iterator = orphans.values().iterator(); iterator.hasNext();) {
                    var orphan = iterator.next().transaction();
                    try {
                        validation.admit(orphan);
                        iterator.remove();
                        announceTransaction(orphan, null);
                        progress = true;
                    } catch (MempoolAdmissionException exception) {
                        if (!exception.getMessage().startsWith("Missing UTXO:")) iterator.remove();
                    } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                             | ru.bitcoin.node.script.ScriptExecutionException | ru.bitcoin.node.script.ScriptParseException exception) {
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
                 | ru.bitcoin.node.script.ScriptExecutionException | ru.bitcoin.node.script.ScriptParseException exception) {
            log.debug("Rejected invalid transaction {}", transaction.txId());
        }
    }

    /**
     * GETDATA is valid up to the protocol inventory limit. Keep the complete request as one
     * bounded per-peer task instead of expanding it into tens of thousands of queued send tasks.
     */
    private void queueGetData(Peer peer, GetDataMessage request) {
        PeerOutbound sender = outbound.get(peer);
        if (sender == null || !sender.execute(() -> serveData(peer, request))) {
            disconnect(peer);
        }
    }

    /** Runs only on this peer's outbound worker, so a slow socket cannot stall other peers. */
    private void serveData(Peer peer, GetDataMessage request) throws IOException {
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

            if (vector.type() == InventoryVector.MSG_BLOCK || vector.type() == InventoryVector.MSG_WITNESS_BLOCK) {
                var block = validation.findBlock(vector.hash());
                if (block.isPresent()) {
                    sendDirect(peer, new BitcoinMessage("block", vector.type() == InventoryVector.MSG_BLOCK
                            ? BlockSerializer.serializeLegacy(block.get()) : BlockSerializer.serialize(block.get())));
                    continue;
                }
            } else if (vector.type() == InventoryVector.MSG_TX
                    || vector.type() == InventoryVector.MSG_WITNESS_TX
                    || vector.type() == MSG_WTX) {
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
            broadcast(BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(InventoryVector.MSG_BLOCK, block.hash())))), null);
        }
        return result;
    }

    public Hash256 submitTransaction(Transaction transaction) {
        validation.admit(transaction);
        announceTransaction(transaction, null);
        return transaction.txId();
    }

    private void announceTransaction(Transaction transaction, Peer source) {
        for (Peer peer : peers.readyPeers()) {
            if (peer == source || !peer.remoteVersion().relay()) continue;
            send(peer, BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(
                    peer.remoteWtxidRelay() ? MSG_WTX : InventoryVector.MSG_TX,
                    peer.remoteWtxidRelay() ? transaction.wtxId() : transaction.txId())))));
        }
    }

    private void broadcast(BitcoinMessage message, Peer source) {
        for (Peer peer : peers.readyPeers()) if (peer != source) send(peer, message);
    }

    /** Queue ordinary outbound traffic on the same per-peer serial worker as GETDATA responses. */
    private void send(Peer peer, BitcoinMessage message) {
        if (closed) return;
        PeerOutbound sender = outbound.get(peer);
        if (sender == null || !sender.execute(() -> sendDirect(peer, message))) {
            disconnect(peer);
        }
    }

    /** Must only be called by the peer's PeerOutbound worker. */
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
        sendTimeouts.shutdownNow();
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    /** One bounded serial send/work queue per peer. */
    private final class PeerOutbound {
        private final ThreadPoolExecutor executor;

        private PeerOutbound(Peer peer) {
            executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(MAX_OUTBOUND_TASKS_PER_PEER),
                    Thread.ofPlatform().daemon().name("bitcoin-relay-peer-", 0).factory());
        }

        private boolean execute(IoTask task) {
            if (closed || executor.isShutdown()) return false;
            try {
                executor.execute(() -> {
                    if (closed) return;
                    try {
                        task.run();
                    } catch (IOException | IllegalStateException exception) {
                        disconnectPeer(exception);
                    } catch (RuntimeException exception) {
                        log.error("Unable to serve outbound peer work", exception);
                        disconnectPeer(exception);
                    }
                });
                return true;
            } catch (RejectedExecutionException exception) {
                return false;
            }
        }

        private void disconnectPeer(Exception cause) {
            log.debug("Outbound relay failed", cause);
            // The close listener removes and shuts down this PeerOutbound.
            for (var entry : outbound.entrySet()) {
                if (entry.getValue() == this) {
                    disconnect(entry.getKey());
                    return;
                }
            }
        }

        private void shutdownNow() {
            executor.shutdownNow();
        }
    }
}
