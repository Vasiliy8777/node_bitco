package ru.bitcoin.node.p2p.address;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class KnownPeerAddress {

    private PeerAddress peerAddress;

    private final PeerAddressSource source;

    private final Instant firstSeen;

    private Instant lastSeen;
    private Instant lastAttempt;
    private Instant lastSuccess;

    private int attempts;

    private AddrManState state =
            AddrManState.NEW;

    private int newBucketReferences;

    KnownPeerAddress(
            PeerAddress peerAddress,
            PeerAddressSource source,
            Instant firstSeen
    ) {

        this.peerAddress =
                Objects.requireNonNull(
                        peerAddress,
                        "peerAddress"
                );

        this.source =
                Objects.requireNonNull(
                        source,
                        "source"
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

    public PeerAddressSource source() {
        return source;
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

    public synchronized AddrManState state() {
        return state;
    }

    public synchronized boolean isNew() {
        return state == AddrManState.NEW;
    }

    public synchronized boolean isTried() {
        return state == AddrManState.TRIED;
    }

    public synchronized int newBucketReferences() {
        return newBucketReferences;
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

        lastAttempt =
                time;

        attempts =
                0;
    }

    synchronized void promoteToTried() {

        state =
                AddrManState.TRIED;

        newBucketReferences =
                0;
    }

    synchronized void demoteToNew() {

        state =
                AddrManState.NEW;
    }

    synchronized void incrementNewBucketReferences() {

        newBucketReferences++;
    }

    synchronized void decrementNewBucketReferences() {

        if (newBucketReferences > 0) {
            newBucketReferences--;
        }
    }

    synchronized void resetNewBucketReferences() {

        newBucketReferences =
                0;
    }
}