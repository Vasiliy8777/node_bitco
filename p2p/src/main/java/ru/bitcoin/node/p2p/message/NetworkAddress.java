package ru.bitcoin.node.p2p.message;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public final class NetworkAddress {

    public static final int SERIALIZED_LENGTH = 26;

    private final long services;
    private final byte[] address;
    private final int port;

    public NetworkAddress(
            long services,
            byte[] address,
            int port
    ) {
        if (address == null) {
            throw new IllegalArgumentException(
                    "address must not be null"
            );
        }

        if (address.length != 16) {
            throw new IllegalArgumentException(
                    "Bitcoin network address must contain 16 bytes"
            );
        }

        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid port"
            );
        }

        this.services = services;
        this.address = address.clone();
        this.port = port;
    }

    public static NetworkAddress fromIp(
            long services,
            InetAddress address,
            int port
    ) {
        if (address == null) {
            throw new IllegalArgumentException(
                    "address must not be null"
            );
        }

        byte[] raw = address.getAddress();

        if (raw.length == 16) {
            return new NetworkAddress(
                    services,
                    raw,
                    port
            );
        }

        if (raw.length == 4) {
            byte[] mapped = new byte[16];

            mapped[10] = (byte) 0xff;
            mapped[11] = (byte) 0xff;

            System.arraycopy(
                    raw,
                    0,
                    mapped,
                    12,
                    4
            );

            return new NetworkAddress(
                    services,
                    mapped,
                    port
            );
        }

        throw new IllegalArgumentException(
                "Unsupported IP address length"
        );
    }

    public static NetworkAddress unspecified() {
        try {
            return fromIp(
                    0,
                    InetAddress.getByName("0.0.0.0"),
                    0
            );
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public long services() {
        return services;
    }

    public byte[] address() {
        return address.clone();
    }

    public int port() {
        return port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof NetworkAddress that)) {
            return false;
        }

        return services == that.services
                && port == that.port
                && Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(services);
        result = 31 * result + Arrays.hashCode(address);
        result = 31 * result + port;
        return result;
    }
}