package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.util.Objects;

/** IOException carrying the local reason and origin that initiated peer close. */
public final class PeerCloseException extends IOException {
    private final PeerCloseReason reason;
    private final String origin;

    public PeerCloseException(PeerCloseReason reason, String origin, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    public PeerCloseException(PeerCloseReason reason, String origin, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    public PeerCloseReason reason() { return reason; }
    public String origin() { return origin; }
}
