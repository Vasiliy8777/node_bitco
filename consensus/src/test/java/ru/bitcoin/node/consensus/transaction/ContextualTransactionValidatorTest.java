package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ContextualTransactionValidatorTest {
    @Test
    void shouldCalculateTransactionFee() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "11".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        7_000L
                );

        UtxoView utxoView =
                outPoint ->
                        outPoint.equals(previousOutput)
                                ? Optional.of(
                                new UtxoEntry(
                                        10_000L,
                                        new byte[]{0x51},
                                        100,
                                        false
                                )
                        )
                                : Optional.empty();

        TransactionContextResult result =
                ContextualTransactionValidator.validate(
                        transaction,
                        200,
                        utxoView
                );

        assertEquals(
                10_000L,
                result.inputValue()
        );

        assertEquals(
                7_000L,
                result.outputValue()
        );

        assertEquals(
                3_000L,
                result.fee()
        );
    }
    private static Transaction transaction(
            OutPoint previousOutput,
            long outputValue
    ) {
        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                previousOutput,
                                new byte[0],
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                outputValue,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }
    @Test
    void shouldRejectMissingInput() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "22".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        1_000L
                );

        UtxoView emptyView =
                outPoint -> Optional.empty();

        assertThrows(
                TransactionValidationException.class,
                () ->
                        ContextualTransactionValidator.validate(
                                transaction,
                                200,
                                emptyView
                        )
        );
    }
    @Test
    void shouldRejectTransactionSpendingMoreThanInputs() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "33".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        10_001L
                );

        UtxoView utxoView =
                outPoint ->
                        Optional.of(
                                new UtxoEntry(
                                        10_000L,
                                        new byte[]{0x51},
                                        100,
                                        false
                                )
                        );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        ContextualTransactionValidator.validate(
                                transaction,
                                200,
                                utxoView
                        )
        );
    }
    @Test
    void shouldRejectImmatureCoinbaseSpend() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "44".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        9_000L
                );

        UtxoView utxoView =
                outPoint ->
                        Optional.of(
                                new UtxoEntry(
                                        10_000L,
                                        new byte[]{0x51},
                                        100,
                                        true
                                )
                        );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        ContextualTransactionValidator.validate(
                                transaction,
                                199,
                                utxoView
                        )
        );
    }
    @Test
    void shouldAllowCoinbaseSpendAtMaturityBoundary() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "55".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        9_000L
                );

        UtxoView utxoView =
                outPoint ->
                        Optional.of(
                                new UtxoEntry(
                                        10_000L,
                                        new byte[]{0x51},
                                        100,
                                        true
                                )
                        );

        TransactionContextResult result =
                ContextualTransactionValidator.validate(
                        transaction,
                        200,
                        utxoView
                );

        assertEquals(
                1_000L,
                result.fee()
        );
    }
}
