package ru.bitcoin.node.p2p.sync;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

public final class HeaderSynchronizationTimeoutException
        extends IOException {

    private final Duration timeout;

    public HeaderSynchronizationTimeoutException(
            Duration timeout
    ) {
        super(
                "Peer did not respond to getheaders within "
                        + Objects.requireNonNull(
                        timeout,
                        "timeout"
                )
        );

        this.timeout =
                timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}