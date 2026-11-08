package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.chain.BlockLocatorBuilder;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.sync.*;
import java.io.IOException;
import java.time.*;
import java.util.function.*;

/** Connects Core's per-peer header timer to the production GETHEADERS waiter. */
final class CoreHeadersResponseControl implements HeaderSynchronizer.ResponseControl {
    private final Peer peer;
    private final PeerManager peers;
    private final HeaderChainState headers;
    private final long spacing;
    private final BooleanSupplier blocksInFlight;
    private final LongSupplier microseconds;
    private final BlockLocatorBuilder initialLocators;
    CoreHeadersResponseControl(Peer peer, PeerManager peers, HeaderChainState headers, long spacing, BooleanSupplier blocksInFlight) {
        this(peer, peers, headers, spacing, blocksInFlight, CoreHeadersResponseControl::nowMicros);
    }
    CoreHeadersResponseControl(Peer peer, PeerManager peers, HeaderChainState headers, long spacing,
                                BooleanSupplier blocksInFlight, LongSupplier microseconds) {
        this(peer, peers, headers, spacing, blocksInFlight, microseconds, null);
    }
    CoreHeadersResponseControl(Peer peer, PeerManager peers, HeaderChainState headers, long spacing,
                                BooleanSupplier blocksInFlight, LongSupplier microseconds, BlockLocatorBuilder initialLocators) {
        this.peer = peer; this.peers = peers; this.headers = headers; this.spacing = spacing;
        this.blocksInFlight = blocksInFlight; this.microseconds = microseconds;
        this.initialLocators = initialLocators;
    }
    CoreHeadersResponseControl(Peer peer, PeerManager peers, HeaderChainState headers, long spacing,
                                BooleanSupplier blocksInFlight, BlockLocatorBuilder initialLocators) {
        this(peer, peers, headers, spacing, blocksInFlight, CoreHeadersResponseControl::nowMicros, initialLocators);
    }
    static long nowMicros() { var now = Instant.now(); return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000; }
    private long bestSeconds() { return headers.publishedBestHeaderTip().header().timestamp().value(); }
    @Override public boolean trySend(GetHeadersMessage request) throws IOException {
        return trySend(request, false);
    }
    boolean trySendAsync(GetHeadersMessage request) throws IOException {
        return trySend(request, true);
    }
    private boolean trySend(GetHeadersMessage request, boolean asynchronous) throws IOException {
        if (!peers.tryBeginHeadersSend(peer)) return false;
        PeerManager.HeaderSyncReservation reservation = null;
        boolean sent = false;
        try {
            long now = microseconds.getAsLong();
            var best = headers.publishedBestHeaderTip();
            reservation = peers.reserveHeadersSync(peer, now, now / 1_000_000, best.header().timestamp().value(), spacing, blocksInFlight.getAsBoolean());
            if (!reservation.allowed()) return false;
            if (reservation.newlyStarted() && initialLocators != null)
                request = new GetHeadersMessage(request.protocolVersion(),
                        initialLocators.buildBeforeTip(best), request.stopHash());
            sent = asynchronous ? peer.trySendGetHeadersAsync(request) : peer.trySendGetHeaders(request); return sent;
        }
        finally {
            if (!sent && reservation != null) peers.rollbackHeadersSync(peer, reservation);
            peers.endHeadersSend(peer);
        }
    }
    @Override public void waiting() throws IOException {
        if (peer.state() == PeerState.CLOSED) throw new IOException("Header peer disconnected");
        long now = microseconds.getAsLong();
        long budget = peers.headersSyncBudgetMicros(peer);
        var action = peers.headersSyncTimeout(peer, now, now / 1_000_000, bestSeconds());
        if (action != HeadersSyncTimeoutPolicy.Action.NONE)
            throw new HeaderSynchronizationTimeoutException(Duration.ofNanos(Math.multiplyExact(budget, 1000)), "Timeout downloading initial headers");
    }
    @Override public void validated() { long now = microseconds.getAsLong(); peers.observeHeadersTip(peer, now / 1_000_000, bestSeconds()); }
}
