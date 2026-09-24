package ru.bitcoin.node.p2p;

/** Semantic role of a managed P2P connection. */
public enum PeerConnectionRole {
    INBOUND,
    FULL_RELAY,
    BLOCK_RELAY_ONLY,
    FEELER;

    public boolean relaysTransactions() {
        return this == INBOUND || this == FULL_RELAY;
    }

    public boolean relaysAddresses() {
        return this == INBOUND || this == FULL_RELAY;
    }

    public boolean persistentOutbound() {
        return this == FULL_RELAY || this == BLOCK_RELAY_ONLY;
    }
}
