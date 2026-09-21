package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.Objects;

public record PeerAddress(
        InetAddress address,
        int port,
        long services
) {

    public PeerAddress {
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

    public String hostAddress() {
        return address.getHostAddress();
    }
}