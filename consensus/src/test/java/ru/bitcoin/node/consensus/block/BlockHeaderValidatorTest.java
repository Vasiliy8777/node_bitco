package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockHeaderValidatorTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static BlockHeader regtestGenesis() {
        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87"
                                + "f618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1296688602L),
                new UInt32(0x207fffffL),
                new UInt32(2)
        );
    }

    @Test
    void shouldAcceptValidHeader() {

        BlockHeader header =
                regtestGenesis();

        assertDoesNotThrow(
                () -> BlockHeaderValidator.validate(
                        header,
                        new UInt32(0x207fffffL),
                        1296688601L,
                        REGTEST
                )
        );
    }

    @Test
    void shouldRejectTimestampEqualToMedianTimePast() {

        BlockHeader header =
                regtestGenesis();

        assertThrows(
                BlockHeaderValidationException.class,
                () -> BlockHeaderValidator.validate(
                        header,
                        new UInt32(0x207fffffL),
                        header.timestamp().value(),
                        REGTEST
                )
        );
    }

    @Test
    void shouldRejectTimestampBelowMedianTimePast() {

        BlockHeader header =
                regtestGenesis();

        assertThrows(
                BlockHeaderValidationException.class,
                () -> BlockHeaderValidator.validate(
                        header,
                        new UInt32(0x207fffffL),
                        header.timestamp().value() + 1,
                        REGTEST
                )
        );
    }

    @Test
    void shouldRejectUnexpectedBits() {

        BlockHeader header =
                regtestGenesis();

        assertThrows(
                BlockHeaderValidationException.class,
                () -> BlockHeaderValidator.validate(
                        header,
                        new UInt32(0x1d00ffffL),
                        1296688601L,
                        REGTEST
                )
        );
    }

    @Test
    void shouldRejectInvalidProofOfWork() {

        /*
         * Очень маленький target:
         *
         * bits = 0x03000001
         *
         * CompactTarget.decode(...) даст target = 1.
         *
         * Вероятность, что SHA-256² заголовка будет <= 1,
         * практически нулевая, но здесь лучше сказать точнее:
         * для конкретного фиксированного header результат
         * детерминирован.
         */
        UInt32 veryHardBits =
                new UInt32(
                        0x03000001L
                );

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        Hash256.fromDisplayHex(
                                "4a5e1e4baab89f3a32518a88c31bc87"
                                        + "f618f76673e2cc77ab2127b7afdeda33b"
                        ),
                        new UInt32(
                                1296688602L
                        ),
                        veryHardBits,
                        new UInt32(2)
                );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> BlockHeaderValidator.validate(
                        header,
                        veryHardBits,
                        1296688601L,
                        REGTEST
                )
        );
    }
}