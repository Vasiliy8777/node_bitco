package ru.bitcoin.node.app.service;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.PeerMessageListener;
import ru.bitcoin.node.p2p.codec.CompactFilterMessageCodec;
import ru.bitcoin.node.p2p.message.BitcoinMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves the already-persisted basic block-filter index to peers using BIP157. */
public final class CompactBlockFilterPeerService implements AutoCloseable {
    private static final Hash256 ZERO = new Hash256(new byte[32]);
    private static final int QUEUE_CAPACITY = 256;

    private final PeerManager peers;
    private final NodeValidationService validation;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final java.util.function.Consumer<Peer> connections = this::attach;
    private final PeerMessageListener messages = this::onMessage;

    public CompactBlockFilterPeerService(PeerManager peers, NodeValidationService validation, boolean enabled) {
        this.peers = Objects.requireNonNull(peers, "peers");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.enabled = enabled;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
            Thread t = new Thread(r, "bip157-filter-server");
            t.setDaemon(true);
            return t;
        }, (task, rejectedExecutor) -> {
            throw new java.util.concurrent.RejectedExecutionException("BIP157 work queue saturated");
        });
        if (enabled) peers.addPeerListener(connections);
    }

    private void attach(Peer peer) {
        peer.addMessageListener(messages);
        peer.addCloseListener((source, cause) -> source.removeMessageListener(messages));
    }

    private void onMessage(Peer peer, BitcoinMessage message) {
        if (!enabled) return;
        switch (message.command()) {
            case "getcfilters", "getcfheaders", "getcfcheckpt" -> {
                try {
                    executor.execute(() -> serve(peer, message));
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    if (!executor.isShutdown()) {
                        peer.disconnectForProtocolViolation("BIP157 request queue saturated", exception);
                    }
                }
            }
            default -> { }
        }
    }

    private void serve(Peer peer, BitcoinMessage message) {
        if (!peer.isReady()) return;
        try {
            switch (message.command()) {
                case "getcfilters" -> serveFilters(peer, CompactFilterMessageCodec.decodeRangeRequest(message.payload()));
                case "getcfheaders" -> serveHeaders(peer, CompactFilterMessageCodec.decodeRangeRequest(message.payload()));
                case "getcfcheckpt" -> serveCheckpoints(peer, CompactFilterMessageCodec.decodeCheckpointRequest(message.payload()));
                default -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // BIP157 unsupported/invalid/unknown requests receive no response.
        } catch (IOException ignored) {
            // Peer close/write failure is handled by the normal connection lifecycle.
        }
    }

    private void serveFilters(Peer peer, CompactFilterMessageCodec.RangeRequest request) throws IOException {
        Range range = range(request.filterType(), request.startHeight(), request.stopHash(), CompactFilterMessageCodec.MAX_CFILTERS);
        if (range == null) return;
        for (long height = range.startHeight(); height <= range.stopHeight(); height++) {
            var block = validation.activeBlockInfo(height).orElseThrow();
            var filter = validation.blockFilter(block.index().hash()).orElseThrow();
            peer.sendAsync(new BitcoinMessage("cfilter", CompactFilterMessageCodec.encodeCFilter(
                    CompactFilterMessageCodec.BASIC_FILTER_TYPE, block.index().hash(), filter.filter())));
        }
    }

    private void serveHeaders(Peer peer, CompactFilterMessageCodec.RangeRequest request) throws IOException {
        Range range = range(request.filterType(), request.startHeight(), request.stopHash(), CompactFilterMessageCodec.MAX_CFHEADERS);
        if (range == null) return;
        Hash256 previous = ZERO;
        if (range.startHeight() > 0) {
            var previousBlock = validation.activeBlockInfo(range.startHeight() - 1L).orElseThrow();
            previous = validation.blockFilter(previousBlock.index().hash()).orElseThrow().header();
        }
        List<Hash256> hashes = new ArrayList<>(Math.toIntExact(range.stopHeight() - range.startHeight() + 1L));
        for (long height = range.startHeight(); height <= range.stopHeight(); height++) {
            var block = validation.activeBlockInfo(height).orElseThrow();
            hashes.add(Hash256Digest.hash(validation.blockFilter(block.index().hash()).orElseThrow().filter()));
        }
        peer.sendAsync(new BitcoinMessage("cfheaders", CompactFilterMessageCodec.encodeCfHeaders(
                CompactFilterMessageCodec.BASIC_FILTER_TYPE, request.stopHash(), previous, hashes)));
    }

    private void serveCheckpoints(Peer peer, CompactFilterMessageCodec.CheckpointRequest request) throws IOException {
        if (request.filterType() != CompactFilterMessageCodec.BASIC_FILTER_TYPE) return;
        var stop = validation.activeBlockInfo(request.stopHash()).orElse(null);
        if (stop == null) return;
        List<Hash256> headers = new ArrayList<>();
        for (long height = CompactFilterMessageCodec.CHECKPOINT_INTERVAL;
             height <= stop.index().height(); height += CompactFilterMessageCodec.CHECKPOINT_INTERVAL) {
            var block = validation.activeBlockInfo(height).orElseThrow();
            headers.add(validation.blockFilter(block.index().hash()).orElseThrow().header());
        }
        peer.sendAsync(new BitcoinMessage("cfcheckpt", CompactFilterMessageCodec.encodeCfCheckpt(
                CompactFilterMessageCodec.BASIC_FILTER_TYPE, request.stopHash(), headers)));
    }

    private Range range(int filterType, long startHeight, Hash256 stopHash, int maximum) {
        if (filterType != CompactFilterMessageCodec.BASIC_FILTER_TYPE) return null;
        var stop = validation.activeBlockInfo(stopHash).orElse(null);
        if (stop == null) return null;
        long stopHeight = stop.index().height();
        if (startHeight > stopHeight) return null;
        long count = stopHeight - startHeight + 1L;
        if (count > maximum) return null;
        return new Range(startHeight, stopHeight);
    }

    @Override
    public void close() {
        if (enabled) {
            peers.removePeerListener(connections);
            for (Peer peer : peers.peers()) peer.removeMessageListener(messages);
        }
        executor.shutdownNow();
    }

    private record Range(long startHeight, long stopHeight) {}
}
