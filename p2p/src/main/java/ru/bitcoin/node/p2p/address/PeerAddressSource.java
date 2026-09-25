package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.Objects;

public final class PeerAddressSource {
    private final PeerAddressNetwork network;
    private final byte[] address;

    private PeerAddressSource(PeerAddressNetwork network, byte[] address) {
        this.network = Objects.requireNonNull(network, "network");
        this.address = Objects.requireNonNull(address, "address").clone();
    }

    public static PeerAddressSource self(PeerAddress peerAddress) {
        Objects.requireNonNull(peerAddress, "peerAddress");
        return new PeerAddressSource(peerAddress.network(), peerAddress.rawAddress());
    }

    public static PeerAddressSource of(InetAddress address) {
        PeerAddress peer = new PeerAddress(address, 1, 0L);
        return new PeerAddressSource(peer.network(), peer.rawAddress());
    }

    public PeerAddressNetwork network() { return network; }
    public byte[] rawAddress() { return address.clone(); }

    public InetAddress address() {
        if (network != PeerAddressNetwork.IPV4 && network != PeerAddressNetwork.IPV6 && network != PeerAddressNetwork.CJDNS) {
            throw new IllegalStateException(network + " source is not an InetAddress");
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("Validated source address length became invalid", exception);
        }
    }
}
