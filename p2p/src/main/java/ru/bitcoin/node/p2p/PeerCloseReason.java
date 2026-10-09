package ru.bitcoin.node.p2p;

/** Stable diagnostic classification for locally initiated peer shutdowns. */
public enum PeerCloseReason {
    LOCAL_BLOCK_TIMEOUT,
    LOCAL_RESOURCE_LIMIT,
    REMOTE_EOF,
    TRANSPORT_READ_FAILURE,
    TRANSPORT_WRITE_FAILURE,
    PROTOCOL_VIOLATION,
    LIVENESS_FAILURE,
    SUPERVISOR_SHUTDOWN,
    FRONTIER_RESCUE,
    LOCAL_REQUEST_FAILURE,
    LOCAL_CLOSE
}
