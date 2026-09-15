package ru.bitcoin.node.mempool.policy;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.mempool.MempoolAdmissionException;
import ru.bitcoin.node.mempool.MempoolPolicy;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MempoolStandardStructurePolicyTest {

    private final MempoolPolicy policy =
            new MempoolPolicy();

    @Test
    void standardPolicyConstantsMustMatchBitcoinCore() {

        assertEquals(
                400_000L,
                MempoolPolicy.MAX_STANDARD_TX_WEIGHT
        );

        assertEquals(
                65,
                MempoolPolicy.MIN_STANDARD_TX_NONWITNESS_SIZE
        );

        assertEquals(
                1_650,
                MempoolPolicy.MAX_STANDARD_SCRIPTSIG_SIZE
        );

        assertEquals(
                1,
                MempoolPolicy.TX_MIN_STANDARD_VERSION
        );

        assertEquals(
                3,
                MempoolPolicy.TX_MAX_STANDARD_VERSION
        );
    }

    @Test
    void transactionBelow65NonWitnessBytesMustFail() {

        /*
         * Minimal fixture:
         *
         * version       4
         * vin count     1
         * input        41
         * vout count    1
         * output:
         *   value       8
         *   script len  1
         *   script      4
         * locktime      4
         *
         * total = 64 bytes.
         */
        Transaction transaction =
                createTransaction(
                        new byte[4]
                );

        int strippedSize =
                TransactionSerializer.serializeLegacy(
                        transaction
                ).length;

        assertEquals(
                64,
                strippedSize
        );

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                weight
                        )
        );
    }

    @Test
    void transactionExactly65NonWitnessBytesMustPassSizeRule() {

        Transaction transaction =
                createTransaction(
                        new byte[]{0x6a, 3, 1, 2, 3}
                );

        int strippedSize =
                TransactionSerializer.serializeLegacy(
                        transaction
                ).length;

        assertEquals(
                65,
                strippedSize
        );

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        assertDoesNotThrow(
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                weight
                        )
        );
    }

    @Test
    void transactionAboveMaximumStandardWeightMustFail() {

        /*
         * Большой scriptPubKey делает transaction
         * больше 400,000 WU.
         *
         * Это тест именно policy method:
         * script здесь не выполняется.
         */
        Transaction transaction =
                createTransaction(
                        new byte[100_000]
                );

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        assertTrue(
                weight
                        > MempoolPolicy.MAX_STANDARD_TX_WEIGHT
        );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                weight
                        )
        );
    }

    @Test
    void ordinaryTransactionMustPassStructuralPolicy() {

        Transaction transaction =
                createTransaction(
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        assertDoesNotThrow(
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                weight
                        )
        );
    }

    @Test
    void invalidArgumentsMustFail() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateStandardStructure(
                                null,
                                100L
                        )
        );

        Transaction transaction =
                createTransaction(
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                0L
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                -1L
                        )
        );
    }

    private static Transaction createTransaction(
            byte[] scriptPubKey
    ) {
        return createTransaction(
                2,
                new byte[0],
                scriptPubKey
        );
    }

    private static Transaction createTransaction(
            int version,
            byte[] scriptSig,
            byte[] scriptPubKey
    ) {
        TxIn input =
                new TxIn(
                        new OutPoint(
                                Hash256.fromDisplayHex(
                                        "11".repeat(32)
                                ),
                                new UInt32(0L)
                        ),
                        scriptSig,
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        TxOut output =
                new TxOut(
                        1L,
                        scriptPubKey
                );

        return new Transaction(
                version,
                List.of(input),
                List.of(output),
                new UInt32(0L)
        );
    }

    @Test
    void versionOneMustBeStandard() {

        Transaction transaction =
                createTransaction(
                        1,
                        new byte[0],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertDoesNotThrow(
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }

    @Test
    void versionThreeMustBeStandard() {

        Transaction transaction =
                createTransaction(
                        3,
                        new byte[0],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertDoesNotThrow(
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }

    @Test
    void versionZeroMustBeNonStandard() {

        Transaction transaction =
                createTransaction(
                        0,
                        new byte[0],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }

    @Test
    void versionFourMustBeNonStandard() {

        Transaction transaction =
                createTransaction(
                        4,
                        new byte[0],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }
    @Test
    void scriptSigExactlyAtStandardLimitMustPass() {

        Transaction transaction =
                createTransaction(
                        2,
                        new byte[
                                MempoolPolicy.MAX_STANDARD_SCRIPTSIG_SIZE
                                ],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertDoesNotThrow(
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }

    @Test
    void scriptSigAboveStandardLimitMustFail() {

        Transaction transaction =
                createTransaction(
                        2,
                        new byte[
                                MempoolPolicy.MAX_STANDARD_SCRIPTSIG_SIZE
                                        + 1
                                ],
                        java.util.HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac")
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateStandardStructure(
                                transaction,
                                TransactionWeight.calculate(
                                        transaction
                                )
                        )
        );
    }
}