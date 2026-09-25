package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.p2p.message.AddrV2Network;

public enum PeerAddressNetwork {
    IPV4(AddrV2Network.IPV4),
    IPV6(AddrV2Network.IPV6),
    TORV3(AddrV2Network.TORV3),
    I2P(AddrV2Network.I2P),
    CJDNS(AddrV2Network.CJDNS);

    private final AddrV2Network wireNetwork;

    PeerAddressNetwork(AddrV2Network wireNetwork) {
        this.wireNetwork = wireNetwork;
    }

    public int bip155Id() { return wireNetwork.id(); }
    public int addressLength() { return wireNetwork.addressLength(); }

    public static PeerAddressNetwork from(AddrV2Network network) {
        return switch (network) {
            case IPV4 -> IPV4;
            case IPV6 -> IPV6;
            case TORV3 -> TORV3;
            case I2P -> I2P;
            case CJDNS -> CJDNS;
            case TORV2 -> throw new IllegalArgumentException("Tor v2 is obsolete and unsupported");
        };
    }
}
