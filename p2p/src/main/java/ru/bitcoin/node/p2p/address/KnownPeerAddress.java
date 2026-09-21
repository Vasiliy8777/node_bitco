package ru.bitcoin.node.p2p.address;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class KnownPeerAddress {

    private PeerAddress peerAddress;

    private final Instant firstSeen;

    private Instant lastSeen;
    private Instant lastAttempt;
    private Instant lastSuccess;

    private int attempts;

    KnownPeerAddress(
            PeerAddress peerAddress,
            Instant firstSeen
    ) {
        this.peerAddress =
                Objects.requireNonNull(
                        peerAddress,
                        "peerAddress"
                );

        this.firstSeen =
                Objects.requireNonNull(
                        firstSeen,
                        "firstSeen"
                );

        this.lastSeen =
                firstSeen;
    }

    public synchronized PeerAddress peerAddress() {
        return peerAddress;
    }

    public Instant firstSeen() {
        return firstSeen;
    }

    public synchronized Instant lastSeen() {
        return lastSeen;
    }

    public synchronized Optional<Instant> lastAttempt() {
        return Optional.ofNullable(
                lastAttempt
        );
    }

    public synchronized Optional<Instant> lastSuccess() {
        return Optional.ofNullable(
                lastSuccess
        );
    }

    public synchronized int attempts() {
        return attempts;
    }

    synchronized void seen(
            PeerAddress updatedAddress,
            Instant time
    ) {
        Objects.requireNonNull(
                updatedAddress,
                "updatedAddress"
        );

        Objects.requireNonNull(
                time,
                "time"
        );

        if (!PeerAddressKey.from(peerAddress)
                .equals(
                        PeerAddressKey.from(
                                updatedAddress
                        )
                )) {

            throw new IllegalArgumentException(
                    "Updated peer address has different endpoint"
            );
        }

        peerAddress =
                updatedAddress;

        if (time.isAfter(lastSeen)) {
            lastSeen =
                    time;
        }
    }

    synchronized void attempted(
            Instant time
    ) {
        Objects.requireNonNull(
                time,
                "time"
        );

        lastAttempt =
                time;

        attempts++;
    }

    synchronized void succeeded(
            Instant time
    ) {
        Objects.requireNonNull(
                time,
                "time"
        );

        lastSuccess =
                time;

        attempts =
                0;
    }
}