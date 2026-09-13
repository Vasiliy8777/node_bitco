package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChainHeaderVersionTest {

    @Test
    void mainnetMustRequireVersionTwoAtBip34Height() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        long activationHeight =
                parameters.bip34Height();

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(1),
                        activationHeight - 1,
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(1),
                        activationHeight,
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(2),
                        activationHeight,
                        parameters
                )
        );
    }

    @Test
    void mainnetMustRequireVersionThreeAtBip66Height() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        long activationHeight =
                parameters.bip66Height();

        /*
         * Перед BIP66 version=2 ещё допустима.
         */
        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(2),
                        activationHeight - 1,
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(2),
                        activationHeight,
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        activationHeight,
                        parameters
                )
        );
    }

    @Test
    void mainnetMustRequireVersionFourAtBip65Height() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        long activationHeight =
                parameters.bip65Height();

        /*
         * Перед BIP65 version=3 ещё допустима.
         */
        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        activationHeight - 1,
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        activationHeight,
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(4),
                        activationHeight,
                        parameters
                )
        );
    }

    @Test
    void testnetMustUseItsConfiguredActivationHeights() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(1),
                        parameters.bip34Height(),
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(2),
                        parameters.bip66Height(),
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        parameters.bip65Height(),
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(4),
                        parameters.bip65Height(),
                        parameters
                )
        );
    }

    @Test
    void regtestMustRequireVersionFourFromHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        /*
         * Все три buried heights у нашего regtest = 1.
         *
         * Поэтому начиная с height=1 итоговое
         * минимальное требование сразу version >= 4.
         */
        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(1),
                        0L,
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        1L,
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(4),
                        1L,
                        parameters
                )
        );
    }

    @Test
    void signetMustRequireVersionFourFromHeightOne() {

        NetworkParameters parameters =
                NetworkParametersRegistry.signet();

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(1),
                        0L,
                        parameters
                )
        );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(3),
                        1L,
                        parameters
                )
        );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validateBlockVersion(
                        header(4),
                        1L,
                        parameters
                )
        );
    }

    private static BlockHeader header(
            int version
    ) {
        Hash256 zero =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        return new BlockHeader(
                version,
                zero,
                zero,
                new UInt32(1L),
                new UInt32(0x207fffffL),
                new UInt32(0L)
        );
    }
}