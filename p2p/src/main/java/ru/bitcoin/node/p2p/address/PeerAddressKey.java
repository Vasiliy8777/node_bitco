package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.Objects;

record PeerAddressKey(
        InetAddress address,
        int port
) {

    PeerAddressKey {
        Objects.requireNonNull(
                address,
                "address"
        );

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port
            );
        }
    }

    static PeerAddressKey from(
            PeerAddress peerAddress
    ) {
        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        return new PeerAddressKey(
                peerAddress.address(),
                peerAddress.port()
        );
    }
}