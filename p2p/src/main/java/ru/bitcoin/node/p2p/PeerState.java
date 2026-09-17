package ru.bitcoin.node.p2p;

public enum PeerState {

    DISCONNECTED,

    CONNECTED,

    VERSION_SENT,

    VERSION_RECEIVED,

    VERACK_SENT,

    READY,

    CLOSED
}