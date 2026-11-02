package ru.bitcoin.node.p2p;

import java.math.BigInteger;
import java.util.List;
import ru.bitcoin.node.common.types.Hash256;

/** Per-connection Core 32.1 chainwork catch-up state. Confined to the recovery scheduler. */
public final class PeerChainSyncPolicy {
    public enum Action { NONE, REQUEST_HEADERS, DISCONNECT }
    private long deadline;
    private BigInteger benchmark;
    private List<Hash256> locator = List.of();
    private boolean requested;

    public Action check(long now, boolean protectedPeer, OutboundPeerRecoveryView.ChainWork work) {
        if (protectedPeer || work == null) return Action.NONE;
        var best = work.peerWork();
        if (best != null && best.compareTo(work.tipWork()) >= 0) {
            deadline = 0; benchmark = null; requested = false;
        } else if (deadline == 0 || (best != null && benchmark != null && best.compareTo(benchmark) >= 0)) {
            deadline = now + 1200; benchmark = work.tipWork();
            locator = List.copyOf(work.locator()); requested = false;
        } else if (now > deadline) {
            if (requested) return Action.DISCONNECT;
            requested = true; deadline = now + 120;
            return Action.REQUEST_HEADERS;
        }
        return Action.NONE;
    }

    public List<Hash256> locator() { return locator; }
}
