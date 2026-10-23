package ru.bitcoin.node.rvn.protocol;

import java.util.List;

public enum RvnNetwork {
    MAINNET(new byte[]{0x52, 0x41, 0x56, 0x4e}, 8767, List.of("seed-raven.bitactivate.com", "seed-raven.ravencoin.com", "seed-raven.ravencoin.org")),
    TESTNET(new byte[]{0x52, 0x56, 0x4e, 0x54}, 18770, List.of("seed-testnet-raven.bitactivate.com", "seed-testnet-raven.ravencoin.com", "seed-testnet-raven.ravencoin.org")),
    REGTEST(new byte[]{0x43, 0x52, 0x4f, 0x57}, 18444, List.of());
    private final byte[] magic;
    private final int port;
    private final List<String> seeds;

    RvnNetwork(byte[] magic, int port, List<String> seeds) {
        this.magic = magic.clone();
        this.port = port;
        this.seeds = List.copyOf(seeds);
    }

    public byte[] magic() {
        return magic.clone();
    }

    public int port() {
        return port;
    }

    public List<String> seeds() {
        return seeds;
    }

    public static RvnNetwork parse(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "mainnet", "main" -> MAINNET;
            case "testnet", "test" -> TESTNET;
            case "regtest" -> REGTEST;
            default -> throw new IllegalArgumentException("Unknown RVN network: " + s);
        };
    }
}
