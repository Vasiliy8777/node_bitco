package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bip30Test {

    @Test
    void shouldNotEnforceForFirstHistoricalMainnetException() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "00000000000a4d0a398161ffc163c503"
                                + "763b1f4360639393e0e4c8e300e0caec"
                );

        assertFalse(
                Bip30.shouldEnforce(
                        91_842L,
                        hash,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    @Test
    void shouldNotEnforceForSecondHistoricalMainnetException() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "00000000000743f190a18c5577a3c2d2"
                                + "a1f610ae9601ac046a38084ccb7cd721"
                );

        assertFalse(
                Bip30.shouldEnforce(
                        91_880L,
                        hash,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    @Test
    void shouldEnforceAtExceptionHeightWithDifferentHash() {

        Hash256 differentHash =
                Hash256.fromDisplayHex(
                        "11".repeat(32)
                );

        assertTrue(
                Bip30.shouldEnforce(
                        91_842L,
                        differentHash,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    @Test
    void shouldEnforceHistoricalHashAtDifferentHeight() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "00000000000a4d0a398161ffc163c503"
                                + "763b1f4360639393e0e4c8e300e0caec"
                );

        assertTrue(
                Bip30.shouldEnforce(
                        91_843L,
                        hash,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    @Test
    void shouldEnforceOnRegtest() {

        Hash256 historicalMainnetHash =
                Hash256.fromDisplayHex(
                        "00000000000a4d0a398161ffc163c503"
                                + "763b1f4360639393e0e4c8e300e0caec"
                );

        assertTrue(
                Bip30.shouldEnforce(
                        91_842L,
                        historicalMainnetHash,
                        NetworkParametersRegistry.regtest()
                )
        );
    }
}