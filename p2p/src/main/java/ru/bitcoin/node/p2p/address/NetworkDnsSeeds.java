package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.protocol.network.BitcoinNetwork;

import java.util.List;
import java.util.Objects;

public final class NetworkDnsSeeds {

    private static final List<String> MAINNET =
            List.of(
                    "dnsseed.bluematt.me",
                    "seed.bitcoin.jonasschnelli.ch",
                    "seed.btc.petertodd.net",
                    "seed.bitcoin.sprovoost.nl",
                    "dnsseed.emzy.de",
                    "seed.bitcoin.wiz.biz",
                    "seed.mainnet.achownodes.xyz"
            );

    private static final List<String> TESTNET =
            List.of(
                    "testnet-seed.bitcoin.jonasschnelli.ch",
                    "seed.tbtc.petertodd.net",
                    "testnet-seed.bluematt.me",
                    "seed.testnet.achownodes.xyz"
            );

    private static final List<String> SIGNET =
            List.of(
                    "seed.signet.bitcoin.sprovoost.nl",
                    "seed.signet.achownodes.xyz"
            );

    private NetworkDnsSeeds() {
    }

    public static List<String> forNetwork(
            BitcoinNetwork network
    ) {
        Objects.requireNonNull(
                network,
                "network"
        );

        return switch (network) {

            case MAINNET ->
                    MAINNET;

            case TESTNET ->
                    TESTNET;

            case SIGNET ->
                    SIGNET;

            case REGTEST ->
                    List.of();
        };
    }
}