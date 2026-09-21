package ru.bitcoin.node.p2p.address;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PeerAddressManager {

    private final Map<
            PeerAddressKey,
            KnownPeerAddress
            > addresses =
            new LinkedHashMap<>();

    public synchronized KnownPeerAddress add(
            PeerAddress peerAddress,
            Instant seenAt
    ) {
        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        Objects.requireNonNull(
                seenAt,
                "seenAt"
        );

        PeerAddressKey key =
                PeerAddressKey.from(
                        peerAddress
                );

        KnownPeerAddress existing =
                addresses.get(
                        key
                );

        if (existing != null) {
            existing.seen(
                    peerAddress,
                    seenAt
            );

            return existing;
        }

        KnownPeerAddress created =
                new KnownPeerAddress(
                        peerAddress,
                        seenAt
                );

        addresses.put(
                key,
                created
        );

        return created;
    }

    public synchronized void addAll(
            Collection<PeerAddress> peerAddresses,
            Instant seenAt
    ) {
        Objects.requireNonNull(
                peerAddresses,
                "peerAddresses"
        );

        Objects.requireNonNull(
                seenAt,
                "seenAt"
        );

        for (PeerAddress peerAddress :
                peerAddresses) {

            add(
                    Objects.requireNonNull(
                            peerAddress,
                            "peerAddress"
                    ),
                    seenAt
            );
        }
    }

    public synchronized Optional<KnownPeerAddress> find(
            PeerAddress peerAddress
    ) {
        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        return Optional.ofNullable(
                addresses.get(
                        PeerAddressKey.from(
                                peerAddress
                        )
                )
        );
    }

    public synchronized void markAttempt(
            PeerAddress peerAddress,
            Instant time
    ) {
        known(
                peerAddress
        ).attempted(
                time
        );
    }

    public synchronized void markSuccess(
            PeerAddress peerAddress,
            Instant time
    ) {
        known(
                peerAddress
        ).succeeded(
                time
        );
    }

    public synchronized List<KnownPeerAddress> addresses() {
        return List.copyOf(
                new ArrayList<>(
                        addresses.values()
                )
        );
    }

    public synchronized int size() {
        return addresses.size();
    }

    public synchronized boolean isEmpty() {
        return addresses.isEmpty();
    }

    private KnownPeerAddress known(
            PeerAddress peerAddress
    ) {
        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        KnownPeerAddress known =
                addresses.get(
                        PeerAddressKey.from(
                                peerAddress
                        )
                );

        if (known == null) {
            throw new IllegalArgumentException(
                    "Unknown peer address: "
                            + peerAddress.hostAddress()
                            + ":"
                            + peerAddress.port()
            );
        }

        return known;
    }
}