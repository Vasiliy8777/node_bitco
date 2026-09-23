package ru.bitcoin.node.p2p.message;

import java.util.Arrays;
import java.util.Optional;

public final class AddrV2Entry {

    public static final int MAX_ADDRESS_LENGTH = 512;

    private static final byte[] IPV4_MAPPED_IPV6_PREFIX = {
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0,
            (byte) 0xFF,
            (byte) 0xFF
    };

    private final long timestamp;
    private final long services;
    private final int networkId;
    private final byte[] address;
    private final int port;

    public AddrV2Entry(
            long timestamp,
            long services,
            int networkId,
            byte[] address,
            int port
    ) {
        if (timestamp < 0
                || timestamp > 0xFFFF_FFFFL) {

            throw new IllegalArgumentException(
                    "timestamp must fit uint32"
            );
        }

        if (services < 0) {
            throw new IllegalArgumentException(
                    "services must fit supported uint64 range"
            );
        }

        if (networkId < 0 || networkId > 0xFF) {
            throw new IllegalArgumentException(
                    "networkId must fit uint8"
            );
        }

        if (address == null) {
            throw new IllegalArgumentException(
                    "address must not be null"
            );
        }

        if (address.length > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    "Address is too long"
            );
        }

        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException(
                    "port must fit uint16"
            );
        }

        Optional<AddrV2Network> knownNetwork =
                AddrV2Network.fromId(networkId);

        if (knownNetwork.isPresent()) {
            int expectedLength =
                    knownNetwork.get().addressLength();

            if (address.length != expectedLength) {
                throw new IllegalArgumentException(
                        "Invalid address length for "
                                + knownNetwork.get()
                                + ": expected "
                                + expectedLength
                                + " but received "
                                + address.length
                );
            }
        }

        this.timestamp = timestamp;
        this.services = services;
        this.networkId = networkId;
        this.address = address.clone();
        this.port = port;
    }

    public long timestamp() {
        return timestamp;
    }

    public long services() {
        return services;
    }

    public int networkId() {
        return networkId;
    }

    public Optional<AddrV2Network> network() {
        return AddrV2Network.fromId(networkId);
    }

    public byte[] address() {
        return address.clone();
    }

    public int port() {
        return port;
    }

    public boolean isKnownNetwork() {
        return network().isPresent();
    }

    /**
     * Tor v2 is obsolete and MUST NOT be gossiped.
     */
    public boolean isTorV2() {
        return networkId == AddrV2Network.TORV2.id();
    }

    /**
     * BIP155 v2.1: IPv4-mapped IPv6 has a canonical
     * representation as networkId IPV4 and should therefore
     * be ignored when received as IPV6.
     */
    public boolean isIpv4MappedIpv6() {
        if (networkId != AddrV2Network.IPV6.id()
                || address.length != 16) {

            return false;
        }

        for (int i = 0;
             i < IPV4_MAPPED_IPV6_PREFIX.length;
             i++) {

            if (address[i]
                    != IPV4_MAPPED_IPV6_PREFIX[i]) {

                return false;
            }
        }

        return true;
    }

    /**
     * Whether this address may later be admitted into AddrMan
     * and relayed to peers.
     *
     * More network-specific validation will be added at the
     * address-manager layer.
     */
    public boolean isGossipEligible() {
        return isKnownNetwork()
                && !isTorV2()
                && !isIpv4MappedIpv6();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof AddrV2Entry other)) {
            return false;
        }

        return timestamp == other.timestamp
                && services == other.services
                && networkId == other.networkId
                && port == other.port
                && Arrays.equals(
                address,
                other.address
        );
    }

    @Override
    public int hashCode() {
        int result =
                Long.hashCode(timestamp);

        result =
                31 * result
                        + Long.hashCode(services);

        result =
                31 * result
                        + networkId;

        result =
                31 * result
                        + Arrays.hashCode(address);

        result =
                31 * result
                        + port;

        return result;
    }
}