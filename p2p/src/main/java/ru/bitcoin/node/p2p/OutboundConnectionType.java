package ru.bitcoin.node.p2p;

/**
 * Purpose of an automatically initiated outbound P2P connection.
 */
public enum OutboundConnectionType {

    /**
     * Long-lived normal outbound peer.
     */
    FULL_RELAY,

    /**
     * Short-lived AddrMan liveness probe.
     */
    FEELER
}