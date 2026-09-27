package ru.bitcoin.node.p2p;

/**
 * Outbound Bitcoin P2P transport selection.
 * AUTO prefers BIP324 v2 and reconnects with v1 if v2 negotiation fails.
 * V2_ONLY never downgrades; V1_ONLY never sends a BIP324 ElligatorSwift key.
 */
public enum PeerTransportPolicy {
    AUTO,
    V1_ONLY,
    V2_ONLY
}
