package ru.bitcoin.node.p2p.message;

import java.util.Optional;

public enum AddrV2Network {

    IPV4(0x01, 4),
    IPV6(0x02, 16),
    TORV2(0x03, 10),
    TORV3(0x04, 32),
    I2P(0x05, 32),
    CJDNS(0x06, 16),
    YGGDRASIL(0x07, 16);

    private final int id;
    private final int addressLength;

    AddrV2Network(
            int id,
            int addressLength
    ) {
        this.id = id;
        this.addressLength = addressLength;
    }

    public int id() {
        return id;
    }

    public int addressLength() {
        return addressLength;
    }

    public static Optional<AddrV2Network> fromId(
            int id
    ) {
        for (AddrV2Network network : values()) {
            if (network.id == id) {
                return Optional.of(network);
            }
        }

        return Optional.empty();
    }
}