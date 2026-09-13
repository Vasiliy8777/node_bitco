package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacySignatureHashTest {

    @Test
    void sighashAllShouldReplaceOnlyCurrentInputScript() {

        Transaction transaction =
                transaction();

        byte[] scriptCode =
                new byte[]{
                        0x51,
                        0x52
                };

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        scriptCode,
                        SignatureHashType.SIGHASH_ALL
                );

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        transaction.inputs()
                                                .get(0)
                                                .previousOutput(),
                                        new byte[0],
                                        transaction.inputs()
                                                .get(0)
                                                .sequence()
                                ),
                                new TxIn(
                                        transaction.inputs()
                                                .get(1)
                                                .previousOutput(),
                                        scriptCode,
                                        transaction.inputs()
                                                .get(1)
                                                .sequence()
                                )
                        ),
                        transaction.outputs(),
                        transaction.lockTime()
                );

        byte[] expected =
                hashWithType(
                        expectedTransaction,
                        SignatureHashType.SIGHASH_ALL
                );

        assertArrayEquals(
                expected,
                actual
        );
    }

    @Test
    void sighashNoneShouldRemoveOutputsAndZeroOtherSequences() {

        Transaction transaction =
                transaction();

        byte[] scriptCode =
                new byte[]{0x51};

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        scriptCode,
                        SignatureHashType.SIGHASH_NONE
                );

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        transaction.inputs()
                                                .get(0)
                                                .previousOutput(),
                                        new byte[0],
                                        new UInt32(0)
                                ),
                                new TxIn(
                                        transaction.inputs()
                                                .get(1)
                                                .previousOutput(),
                                        scriptCode,
                                        transaction.inputs()
                                                .get(1)
                                                .sequence()
                                )
                        ),
                        List.of(),
                        transaction.lockTime()
                );

        assertArrayEquals(
                hashWithType(
                        expectedTransaction,
                        SignatureHashType.SIGHASH_NONE
                ),
                actual
        );
    }

    @Test
    void sighashSingleShouldKeepCorrespondingOutput() {

        Transaction transaction =
                transaction();

        byte[] scriptCode =
                new byte[]{0x51};

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        scriptCode,
                        SignatureHashType.SIGHASH_SINGLE
                );

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        transaction.inputs()
                                                .get(0)
                                                .previousOutput(),
                                        new byte[0],
                                        new UInt32(0)
                                ),
                                new TxIn(
                                        transaction.inputs()
                                                .get(1)
                                                .previousOutput(),
                                        scriptCode,
                                        transaction.inputs()
                                                .get(1)
                                                .sequence()
                                )
                        ),
                        List.of(
                                new TxOut(
                                        -1L,
                                        new byte[0]
                                ),
                                transaction.outputs()
                                        .get(1)
                        ),
                        transaction.lockTime()
                );

        assertArrayEquals(
                hashWithType(
                        expectedTransaction,
                        SignatureHashType.SIGHASH_SINGLE
                ),
                actual
        );
    }

    @Test
    void anyoneCanPayShouldKeepOnlyCurrentInput() {

        Transaction transaction =
                transaction();

        int hashType =
                SignatureHashType.SIGHASH_ALL
                        |
                        SignatureHashType.SIGHASH_ANYONECANPAY;

        byte[] scriptCode =
                new byte[]{0x51};

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        scriptCode,
                        hashType
                );

        TxIn current =
                transaction.inputs()
                        .get(1);

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        current.previousOutput(),
                                        scriptCode,
                                        current.sequence()
                                )
                        ),
                        transaction.outputs(),
                        transaction.lockTime()
                );

        assertArrayEquals(
                hashWithType(
                        expectedTransaction,
                        hashType
                ),
                actual
        );
    }

    @Test
    void sighashSingleAnyoneCanPayShouldCombineBothRules() {

        Transaction transaction =
                transaction();

        int hashType =
                SignatureHashType.SIGHASH_SINGLE
                        |
                        SignatureHashType.SIGHASH_ANYONECANPAY;

        byte[] scriptCode =
                new byte[]{0x51};

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        scriptCode,
                        hashType
                );

        TxIn current =
                transaction.inputs()
                        .get(1);

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        current.previousOutput(),
                                        scriptCode,
                                        current.sequence()
                                )
                        ),
                        List.of(
                                new TxOut(
                                        -1L,
                                        new byte[0]
                                ),
                                transaction.outputs()
                                        .get(1)
                        ),
                        transaction.lockTime()
                );

        assertArrayEquals(
                hashWithType(
                        expectedTransaction,
                        hashType
                ),
                actual
        );
    }

    @Test
    void sighashSingleWithoutCorrespondingOutputShouldReturnHashOne() {

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                input(
                                        0x11,
                                        1,
                                        100
                                ),
                                input(
                                        0x22,
                                        2,
                                        200
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        byte[] result =
                LegacySignatureHash.calculate(
                        transaction,
                        1,
                        new byte[]{0x51},
                        SignatureHashType.SIGHASH_SINGLE
                );

        byte[] expected =
                new byte[32];

        expected[0] =
                0x01;

        assertArrayEquals(
                expected,
                result
        );
    }

    @Test
    void returnedHashOneMustBeDefensivelyCopied() {

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                input(
                                        0x11,
                                        1,
                                        100
                                )
                        ),
                        List.of(),
                        new UInt32(0)
                );

        byte[] first =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        new byte[]{0x51},
                        SignatureHashType.SIGHASH_SINGLE
                );

        first[0] =
                0x55;

        byte[] second =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        new byte[]{0x51},
                        SignatureHashType.SIGHASH_SINGLE
                );

        assertEquals(
                0x01,
                Byte.toUnsignedInt(
                        second[0]
                )
        );
    }

    @Test
    void invalidInputIndexShouldFail() {

        Transaction transaction =
                transaction();

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacySignatureHash.calculate(
                        transaction,
                        -1,
                        new byte[0],
                        SignatureHashType.SIGHASH_ALL
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacySignatureHash.calculate(
                        transaction,
                        transaction.inputs().size(),
                        new byte[0],
                        SignatureHashType.SIGHASH_ALL
                )
        );
    }

    private static Transaction transaction() {

        return new Transaction(
                2,
                List.of(
                        input(
                                0x11,
                                1,
                                100
                        ),
                        input(
                                0x22,
                                2,
                                200
                        )
                ),
                List.of(
                        new TxOut(
                                1_000L,
                                new byte[]{
                                        0x51
                                }
                        ),
                        new TxOut(
                                2_000L,
                                new byte[]{
                                        0x52
                                }
                        ),
                        new TxOut(
                                3_000L,
                                new byte[]{
                                        0x53
                                }
                        )
                ),
                new UInt32(500)
        );
    }

    private static TxIn input(
            int hashByte,
            long outputIndex,
            long sequence
    ) {
        byte[] hash =
                new byte[32];

        hash[0] =
                (byte) hashByte;

        return new TxIn(
                new OutPoint(
                        new Hash256(hash),
                        new UInt32(
                                outputIndex
                        )
                ),
                new byte[]{
                        0x01,
                        0x7f
                },
                new UInt32(
                        sequence
                )
        );
    }

    private static byte[] hashWithType(
            Transaction transaction,
            int hashType
    ) {
        byte[] serialized =
                TransactionSerializer.serializeLegacy(
                        transaction
                );

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(
                        serialized.length + 4
                );

        out.writeBytes(
                serialized
        );

        out.writeBytes(
                LittleEndian.uint32(
                        Integer.toUnsignedLong(
                                hashType
                        )
                )
        );

        return Hash256Digest.hashBytes(
                out.toByteArray()
        );
    }
    @Test
    void codeSeparatorInsidePushDataMustNotBeRemoved() {

        Transaction transaction =
                transaction();

        /*
         * 02 ab 51
         *
         * 0x02 = push next two bytes.
         * Поэтому 0xab здесь DATA, а не OP_CODESEPARATOR.
         */
        byte[] script =
                new byte[]{
                        0x02,
                        (byte) Opcode.OP_CODESEPARATOR,
                        (byte) Opcode.OP_1
                };

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        script,
                        SignatureHashType.SIGHASH_ALL
                );

        Transaction expectedTransaction =
                new Transaction(
                        transaction.version(),
                        List.of(
                                new TxIn(
                                        transaction.inputs()
                                                .get(0)
                                                .previousOutput(),
                                        script,
                                        transaction.inputs()
                                                .get(0)
                                                .sequence()
                                ),
                                new TxIn(
                                        transaction.inputs()
                                                .get(1)
                                                .previousOutput(),
                                        new byte[0],
                                        transaction.inputs()
                                                .get(1)
                                                .sequence()
                                )
                        ),
                        transaction.outputs(),
                        transaction.lockTime()
                );

        assertArrayEquals(
                hashWithType(
                        expectedTransaction,
                        SignatureHashType.SIGHASH_ALL
                ),
                actual
        );
    }
    @Test
    void actualCodeSeparatorMustBeRemoved() {

        Transaction transaction =
                transaction();

        byte[] scriptWithSeparator =
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_CODESEPARATOR,
                        (byte) Opcode.OP_2
                };

        byte[] scriptWithoutSeparator =
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_2
                };

        byte[] withSeparator =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        scriptWithSeparator,
                        SignatureHashType.SIGHASH_ALL
                );

        byte[] withoutSeparator =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        scriptWithoutSeparator,
                        SignatureHashType.SIGHASH_ALL
                );

        assertArrayEquals(
                withoutSeparator,
                withSeparator
        );
    }
}