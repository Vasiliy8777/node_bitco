package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;

import java.util.List;

/** Chain-aware eligibility supplied by the validation layer, outside the transport. */
public interface BlockDownloadPeerPolicy {
    BlockDownloadPeerPolicy SERVICES_ONLY = (peer, hash, height) -> height == null
            || ru.bitcoin.node.p2p.LimitedHistoryPeerPolicy.canServeBlockHeight(peer.remoteVersion(), height);

    default void refresh(List<Peer> peers) { }
    boolean canServe(Peer peer, Hash256 hash, Long height);

    default boolean canTraverse(Peer peer, Hash256 hash, Long height) { return true; }

    /** Separate the download window from availability for the window+1 stall probe. */
    default boolean canDownload(Peer peer, Hash256 hash, Long height) {
        return canServe(peer, hash, height);
    }

    default boolean canProbeWindowEnd(Peer peer, Hash256 hash, Long height) {
        return canServe(peer, hash, height);
    }
}
