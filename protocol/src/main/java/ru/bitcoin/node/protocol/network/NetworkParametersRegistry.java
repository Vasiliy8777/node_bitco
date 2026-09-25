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
     * 00000000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF
     */
    private static final BigInteger MAIN_POW_LIMIT =
            new BigInteger(
                    "00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
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
                Hash256.fromDisplayHex(
                        "00000000000002dc756eebf4f49723ed"
                                + "8d30cc28a5f108eb94b1ba88ac4f9c22"
                ),
                MAIN_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                210_000L,
                227_931L,
                363_725L,
                388_381L,
                419_328L,
                481_824L,
                false,
                false,
                false,
                new BigInteger("0000000000000000000000000000000000000001128750f82f4c366153a3a030", 16),
                Hash256.fromDisplayHex("00000000000000000000ccebd6d74d9194d8dcdc1d177c478e094bfad51ba5ac")
        );
    }

    public static NetworkParameters testnet() {
        return new NetworkParameters(
                BitcoinNetwork.TESTNET,
                0x0709110BL,
                18333,
                Hash256.fromDisplayHex(
                        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
                ),
                Hash256.fromDisplayHex(
                        "00000000dd30457c001f4095d208cc12"
                                + "96b0eed002427aa599874af7a432b105"
                ),
                MAIN_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                210_000L,
                21_111L,
                330_776L,
                581_885L,
                770_112L,
                834_624L,
                true,
                false,
                false,
                new BigInteger("0000000000000000000000000000000000000000000017dde1c649f3708d14b6", 16),
                Hash256.fromDisplayHex("000000007a61e4230b28ac5cb6b5e5a0130de37ac1faf2f8987d2fa6505b67f4")
        );
    }

    public static NetworkParameters signet() {
        return new NetworkParameters(
                BitcoinNetwork.SIGNET,
                0x40CF030AL,
                38333,
                Hash256.fromDisplayHex(
                        "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
                ),
                null,
                new BigInteger("00000377ae000000000000000000000000000000000000000000000000000000", 16),
                TARGET_SPACING,
                TARGET_TIMESPAN,
                210_000L,
                1L,
                1L,
                1L,
                1L,
                1L,
                false,
                false,
                false,
                new BigInteger("00000000000000000000000000000000000000000000000000000b463ea0a4b8", 16),
                Hash256.fromDisplayHex("00000008414aab61092ef93f1aacc54cf9e9f16af29ddad493b908a01ff5c329")
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
                null,
                REGTEST_POW_LIMIT,
                TARGET_SPACING,
                TARGET_TIMESPAN,
                150L,
                1L,
                1L,
                1L,
                1L,
                0L,
                true,
                false,
                true,
                BigInteger.ZERO,
                new Hash256(new byte[Hash256.LENGTH])
        );
    }
}
