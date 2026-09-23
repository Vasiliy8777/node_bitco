package ru.bitcoin.node.p2p.message;

import java.net.InetAddress;

public final class AddrEntry {

    public static final int SERIALIZED_LENGTH =
            30;

    private final long timestamp;
    private final NetworkAddress networkAddress;

    public AddrEntry(
            long timestamp,
            NetworkAddress networkAddress
    ) {
        if (timestamp < 0
                || timestamp > 0xFFFF_FFFFL) {

            throw new IllegalArgumentException(
                    "timestamp must fit uint32"
            );
        }

        if (networkAddress == null) {
            throw new IllegalArgumentException(
                    "networkAddress must not be null"
            );
        }

        this.timestamp = timestamp;
        this.networkAddress = networkAddress;
    }

    public static AddrEntry fromIp(
            long timestamp,
            long services,
            InetAddress address,
            int port
    ) {
        return new AddrEntry(
                timestamp,
                NetworkAddress.fromIp(
                        services,
                        address,
                        port
                )
        );
    }

    public long timestamp() {
        return timestamp;
    }

    public NetworkAddress networkAddress() {
        return networkAddress;
    }

    public long services() {
        return networkAddress.services();
    }

    public byte[] address() {
        return networkAddress.address();
    }

    public int port() {
        return networkAddress.port();
    }
}