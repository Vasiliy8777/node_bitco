package ru.bitcoin.node.p2p;

/** Locally granted permissions. Peer VERSION services never grant these rights. */
public enum PeerPermission {
    DOWNLOAD, NOBAN, ADDR, RELAY, FORCERELAY, MEMPOOL
}
