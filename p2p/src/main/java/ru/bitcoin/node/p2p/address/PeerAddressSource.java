package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.Objects;

public record PeerAddressSource(
        InetAddress address
) {

    public PeerAddressSource {
        Objects.requireNonNull(
                address,
                "address"
        );
    }

    public static PeerAddressSource self(
            PeerAddress peerAddress
    ) {

        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        return new PeerAddressSource(
                peerAddress.address()
        );
    }

    public static PeerAddressSource of(
            InetAddress address
    ) {

        return new PeerAddressSource(
                address
        );
    }
}