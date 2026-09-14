package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionWeightTest {

    @Test
    void legacyTransactionWeightMustBeFourTimesSerializedSize() {

        Transaction transaction =
                createLegacyTransaction();

        long serializedSize =
                TransactionSerializer.serialize(
                        transaction
                ).length;

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        assertEquals(
                serializedSize * 4L,
                weight
        );
    }

    @Test
    void witnessTransactionWeightMustUseStrippedAndTotalSize() {

        Transaction transaction =
                createWitnessTransaction();

        long strippedSize =
                TransactionSerializer.serializeLegacy(
                        transaction
                ).length;

        long totalSize =
                TransactionSerializer.serialize(
                        transaction
                ).length;

        long expected =
                strippedSize * 3L
                        + totalSize;

        assertEquals(
                expected,
                TransactionWeight.calculate(
                        transaction
                )
        );
    }

    @Test
    void virtualSizeMustRoundWeightUp() {

        assertEquals(
                100L,
                TransactionWeight.virtualSize(
                        400L
                )
        );

        assertEquals(
                101L,
                TransactionWeight.virtualSize(
                        401L
                )
        );

        assertEquals(
                101L,
                TransactionWeight.virtualSize(
                        402L
                )
        );

        assertEquals(
                101L,
                TransactionWeight.virtualSize(
                        403L
                )
        );

        assertEquals(
                101L,
                TransactionWeight.virtualSize(
                        404L
                )
        );
    }

    @Test
    void negativeWeightMustBeRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TransactionWeight.virtualSize(
                                -1L
                        )
        );
    }

    @Test
    void nullTransactionMustBeRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TransactionWeight.calculate(
                                null
                        )
        );
    }

    private static Transaction createLegacyTransaction() {

        TxIn input =
                new TxIn(
                        new OutPoint(
                                Hash256.fromDisplayHex(
                                        "11".repeat(32)
                                ),
                                new UInt32(1L)
                        ),
                        new byte[]{
                                0x01,
                                0x01
                        },
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        TxOut output =
                new TxOut(
                        50_000L,
                        new byte[]{
                                0x51
                        }
                );

        return new Transaction(
                2,
                List.of(input),
                List.of(output),
                new UInt32(0L)
        );
    }

    private static Transaction createWitnessTransaction() {

        Witness witness =
                new Witness(
                        List.of(
                                new byte[]{
                                        0x01,
                                        0x02
                                },
                                new byte[]{
                                        0x03,
                                        0x04,
                                        0x05
                                }
                        )
                );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                Hash256.fromDisplayHex(
                                        "22".repeat(32)
                                ),
                                new UInt32(0L)
                        ),
                        new byte[0],
                        new UInt32(
                                0xffff_fffdL
                        ),
                        witness
                );

        TxOut output =
                new TxOut(
                        40_000L,
                        new byte[]{
                                0x51
                        }
                );

        return new Transaction(
                2,
                List.of(input),
                List.of(output),
                new UInt32(0L)
        );
    }
}