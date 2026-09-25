package ru.bitcoin.node.p2p.address;

import java.util.Arrays;
import java.util.Objects;

final class PeerAddressKey {
    private final PeerAddressNetwork network;
    private final byte[] address;
    private final int port;

    private PeerAddressKey(PeerAddressNetwork network, byte[] address, int port) {
        this.network = Objects.requireNonNull(network, "network");
        this.address = address.clone();
        this.port = port;
    }

    static PeerAddressKey from(PeerAddress peerAddress) {
        Objects.requireNonNull(peerAddress, "peerAddress");
        return new PeerAddressKey(peerAddress.network(), peerAddress.rawAddress(), peerAddress.port());
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerAddressKey other)) return false;
        return port == other.port && network == other.network && Arrays.equals(address, other.address);
    }
    @Override public int hashCode() { return 31 * Objects.hash(network, port) + Arrays.hashCode(address); }
}
