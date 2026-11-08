package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.BlockLocatorBuilder;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Availability probes during block IBD share the initial/live headers state. */
final class CoreAvailabilityHeaders implements CoreBlockDownloadPeerPolicy.HeadersRequests {
    private final PeerManager peers;
    private final HeaderChainState headers;
    private final BlockLocatorBuilder locators;
    private final long spacing;
    private final BooleanSupplier blocksInFlight;
    private final LongSupplier clock;

    CoreAvailabilityHeaders(PeerManager peers, HeaderChainState headers, BlockLocatorBuilder locators,
                            long spacing, BooleanSupplier blocksInFlight) {
        this(peers, headers, locators, spacing, blocksInFlight, CoreHeadersResponseControl::nowMicros);
    }
    CoreAvailabilityHeaders(PeerManager peers, HeaderChainState headers, BlockLocatorBuilder locators,
                            long spacing, BooleanSupplier blocksInFlight, LongSupplier clock) {
        this.peers = peers; this.headers = headers; this.locators = locators; this.spacing = spacing;
        this.blocksInFlight = blocksInFlight; this.clock = clock;
    }
    private CoreHeadersResponseControl control(Peer peer) {
        return new CoreHeadersResponseControl(peer, peers, headers, spacing, blocksInFlight, clock, locators);
    }
    @Override public void check(Peer peer) throws IOException { control(peer).waiting(); }
    @Override public boolean trySend(Peer peer, GetHeadersMessage request) throws IOException {
        return control(peer).trySendAsync(request);
    }
}
