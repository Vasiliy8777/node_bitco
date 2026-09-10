package ru.bitcoin.node.protocol.network;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;

public final class NetworkParametersRegistry {

    private static final long TARGET_SPACING =
            10 * 60L;

    private static final long TARGET_TIMESPAN =
            14 * 24 * 60 * 60L;

    /*
     * Bitcoin mainnet/testnet PoW limit:
     *
     * 00000000FFFF0000000000000000000000000000000000000000000000000000
     */
    private static final BigInteger MAIN_POW_LIMIT =
            new BigInteger(
                    "00000000ffff0000000000000000000000000000000000000000000000000000",
                    16
            );

    /*
     * Regtest PoW limit:
     *
     * 7FFF...FFFF
     */
    private static final BigInteger REGTEST_POW_LIMIT =
            new BigInteger(
                    "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    16
            );

    private NetworkParametersRegistry() {
    }

    public static NetworkParameters forNetwork(
            BitcoinNetwork network
    ) {
        if (network == null) {
            throw new IllegalArgumentException(
                    "network must not be null"
            );
        }

        return switch (network) {
            case MAINNET -> mainnet();
            case TESTNET -> testnet();
            case SIGNET -> signet();
            case REGTEST -> regtest();
        };
    }

    public static NetworkParameters mainnet() {
        return new NetworkParameters(
                BitcoinNetwork.MAINNET,
                0xD9B4BEF9L,
                8333,
                Hash256.fromDisplayHex(
                        "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
                ),
                MAIN_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                false,
                false
        );
    }

    public static NetworkParameters testnet() {
        return new NetworkParameters(
                BitcoinNetwork.TESTNET,
                0x0709110BL,
                18333,
                Hash256.fromDisplayHex(
                        "000000000933ea01ad0ee984209779baae8c49c7b00203cc83f10bfa6c712f72"
                ),
                MAIN_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                true,
                false
        );
    }

    public static NetworkParameters signet() {
        return new NetworkParameters(
                BitcoinNetwork.SIGNET,
                0x40CF030AL,
                38333,
                Hash256.fromDisplayHex(
                        "00000008819873e925422c1ff0f99f7c" +
                                "6a0a92672c4b4f75eaa8bdfc22f4b9f9"
                ),
                MAIN_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                false,
                false
        );
    }

    public static NetworkParameters regtest() {
        return new NetworkParameters(
                BitcoinNetwork.REGTEST,
                0xDAB5BFFAL,
                18444,
                Hash256.fromDisplayHex(
                        "0f9188f13cb7b2c71f2a335e3a4fc328" +
                                "bf5beb436012afca590b1a11466e2206"
                ),
                REGTEST_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                true,
                true
        );
    }
}
