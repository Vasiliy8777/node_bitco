package ru.bitcoin.node.consensus.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.script.ScriptVerifyFlags;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusScriptFlagsTest {
    @Test
    void retrospectiveBaseAndBip16ExceptionsMatchCore() {
        int base = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.TAPROOT;
        for (var parameters : new NetworkParameters[]{NetworkParametersRegistry.mainnet(),
                NetworkParametersRegistry.testnet(), NetworkParametersRegistry.signet()}) {
            assertEquals(base, ConsensusScriptFlags.forBlock(0, Hash256.fromDisplayHex("12".repeat(32)), parameters));
            assertEquals(base, ConsensusScriptFlags.forBlock(0, Hash256.fromDisplayHex("12".repeat(32)), parameters, false));
        }
        for (var parameters : new NetworkParameters[]{NetworkParametersRegistry.mainnet(), NetworkParametersRegistry.testnet()}) {
            assertEquals(ScriptVerifyFlags.NONE, ConsensusScriptFlags.forBlock(0,
                    parameters.bip16ExceptionBlockHash(), parameters, true));
        }
    }
    private static final Hash256 NORMAL_BLOCK_HASH =
            Hash256.fromDisplayHex(
                    "11".repeat(32)
            );

    @Test
    void mainnetDerSigMustBeDisabledBeforeBip66() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        int flags =
                ConsensusScriptFlags.forBlock(
                        363_724L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DERSIG
                )
        );
    }

    @Test
    void mainnetDerSigMustActivateAtBip66Height() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        int flags =
                ConsensusScriptFlags.forBlock(
                        363_725L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DERSIG
                )
        );
    }

    @Test
    void testnetDerSigMustActivateAtConfiguredHeight() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertEquals(
                330_776L,
                parameters.bip66Height()
        );

        assertEquals(
                581_885L,
                parameters.bip65Height()
        );

        assertEquals(
                770_112L,
                parameters.csvHeight()
        );

        assertNotEquals(
                ScriptVerifyFlags.P2SH,
                ScriptVerifyFlags.DERSIG
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        330_775L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        330_776L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.DERSIG
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.DERSIG
                )
        );
    }

    @Test
    void regtestDerSigMustBeActiveFromHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        int heightZero =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int heightOne =
                ConsensusScriptFlags.forBlock(
                        1L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        heightZero,
                        ScriptVerifyFlags.DERSIG
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightOne,
                        ScriptVerifyFlags.DERSIG
                )
        );
    }
    @Test
    void mainnetP2shMustNormallyBeEnabled() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        int flags =
                ConsensusScriptFlags.forBlock(
                        100_000L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.P2SH
                )
        );
    }
    @Test
    void mainnetHistoricalBip16ExceptionMustDisableP2sh() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        int flags =
                ConsensusScriptFlags.forBlock(
                        170_060L,
                        parameters.bip16ExceptionBlockHash(),
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.P2SH
                )
        );
    }
    @Test
    void testnetHistoricalBip16ExceptionMustDisableP2sh() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        int flags =
                ConsensusScriptFlags.forBlock(
                        1L,
                        parameters.bip16ExceptionBlockHash(),
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.P2SH
                )
        );
    }
    @Test
    void regtestMustAlwaysEnableP2sh() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        int flags =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.P2SH
                )
        );
    }

    @Test
    void mainnetCltvMustActivateAtBip65Height() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        int before =
                ConsensusScriptFlags.forBlock(
                        388_380L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        388_381L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );
    }
    @Test
    void testnetCltvMustActivateAtConfiguredHeight() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertEquals(
                581_885L,
                parameters.bip65Height()
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        581_884L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        581_885L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );
    }
    @Test
    void signetCltvMustActivateAtHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.signet();

        int heightZero =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int heightOne =
                ConsensusScriptFlags.forBlock(
                        1L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        heightZero,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightOne,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );
    }
    @Test
    void mainnetCsvMustActivateAtConfiguredHeight() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        assertEquals(
                419_328L,
                parameters.csvHeight()
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        419_327L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        419_328L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );
    }
    @Test
    void testnetCsvMustActivateAtConfiguredHeight() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertEquals(
                770_112L,
                parameters.csvHeight()
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        770_111L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        770_112L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );
    }
    @Test
    void regtestCsvMustActivateAtHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        int heightZero =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int heightOne =
                ConsensusScriptFlags.forBlock(
                        1L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        heightZero,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightOne,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );
    }
    @Test
    void signetCsvMustActivateAtHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.signet();

        int heightZero =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int heightOne =
                ConsensusScriptFlags.forBlock(
                        1L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        heightZero,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightOne,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );
    }
    @Test
    void mainnetWitnessIsRetrospective() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        assertEquals(
                481_824L,
                parameters.segwitHeight()
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        481_823L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        481_824L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.WITNESS
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void testnetWitnessIsRetrospective() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertEquals(
                834_624L,
                parameters.segwitHeight()
        );

        int before =
                ConsensusScriptFlags.forBlock(
                        834_623L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        834_624L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.WITNESS
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void signetWitnessIsRetrospective() {

        NetworkParameters parameters =
                NetworkParametersRegistry.signet();

        int heightZero =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        int heightOne =
                ConsensusScriptFlags.forBlock(
                        1L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightZero,
                        ScriptVerifyFlags.WITNESS
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        heightOne,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void regtestWitnessMustBeActiveFromHeightZero() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        assertEquals(
                0L,
                parameters.segwitHeight()
        );

        int flags =
                ConsensusScriptFlags.forBlock(
                        0L,
                        NORMAL_BLOCK_HASH,
                        parameters
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }
    @Test
    void nullDummyMustActivateWithSegwitOnMainnet() {

        NetworkParameters mainnet =
                NetworkParametersRegistry.mainnet();

        Hash256 ordinaryHash =
                Hash256.fromDisplayHex(
                        "11".repeat(32)
                );

        int before =
                ConsensusScriptFlags.forBlock(
                        481_823L,
                        ordinaryHash,
                        mainnet
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        481_824L,
                        ordinaryHash,
                        mainnet
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );
    }
    @Test
    void nullDummyMustActivateWithSegwitOnTestnet() {

        NetworkParameters testnet =
                NetworkParametersRegistry.testnet();

        Hash256 ordinaryHash =
                Hash256.fromDisplayHex(
                        "22".repeat(32)
                );

        int before =
                ConsensusScriptFlags.forBlock(
                        834_623L,
                        ordinaryHash,
                        testnet
                );

        int active =
                ConsensusScriptFlags.forBlock(
                        834_624L,
                        ordinaryHash,
                        testnet
                );

        assertFalse(
                ScriptVerifyFlags.has(
                        before,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        active,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );
    }
    @Test
    void nullDummyMustBeActiveFromGenesisOnRegtest() {

        NetworkParameters regtest =
                NetworkParametersRegistry.regtest();

        int flags =
                ConsensusScriptFlags.forBlock(
                        0L,
                        Hash256.fromDisplayHex(
                                "33".repeat(32)
                        ),
                        regtest
                );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.WITNESS
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );
    }
    @Test
    void taprootExceptionPreservesOtherActiveRules() {
        var hash = Hash256.fromDisplayHex("0000000000000000000f14c35b2d841e986ab5441de8c585d5ffe55ea1e395ad");
        int expected = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS
                | ScriptVerifyFlags.DERSIG | ScriptVerifyFlags.NULLDUMMY
                | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY | ScriptVerifyFlags.CHECKSEQUENCEVERIFY;
        for (boolean active : new boolean[]{false, true}) {
            assertEquals(expected, ConsensusScriptFlags.forBlock(709632, hash,
                    NetworkParametersRegistry.mainnet(), active));
        }
        assertEquals(expected, ConsensusScriptFlags.forBlock(709632, hash,
                NetworkParametersRegistry.mainnet()));
        assertEquals(expected | ScriptVerifyFlags.TAPROOT,
                ConsensusScriptFlags.forBlock(709632, hash, NetworkParametersRegistry.regtest(), true));
    }
}
