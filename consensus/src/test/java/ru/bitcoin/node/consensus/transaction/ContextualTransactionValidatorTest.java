package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.script.ScriptVerifyFlags;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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
    @Test
    void shouldAcceptValidTransactionWithUtxoContext() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            input.previousOutput()
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    2_000L,
                                    new byte[]{0x51},
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldRejectTransactionWithMissingUtxo() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint ->
                        Optional.empty();

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldRejectTransactionWhenOutputsExceedInputs() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input
                        ),
                        List.of(
                                new TxOut(
                                        2_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            input.previousOutput()
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    new byte[]{0x51},
                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldAcceptTransactionWhenInputsEqualOutputs() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            input.previousOutput()
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    new byte[]{0x51},
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldRejectUtxoAmountAboveMaxMoney() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            input.previousOutput()
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    Money.MAX_MONEY + 1,
                                    new byte[]{0x51},
                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldRejectTotalInputAmountAboveMaxMoney() {

        TxIn firstInput =
                input(
                        "11",
                        0
                );

        TxIn secondInput =
                input(
                        "22",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                firstInput,
                                secondInput
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (requestedOutPoint.equals(
                            firstInput.previousOutput()
                    )) {
                        return Optional.of(
                                new UtxoEntry(
                                        Money.MAX_MONEY,
                                        new byte[]{0x51},
                                        100L,
                                        false
                                )
                        );
                    }

                    if (requestedOutPoint.equals(
                            secondInput.previousOutput()
                    )) {
                        return Optional.of(
                                new UtxoEntry(
                                        1L,
                                        new byte[]{0x51},
                                        100L,
                                        false
                                )
                        );
                    }

                    return Optional.empty();
                };

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldRejectTransactionWhenInputScriptValidationFails() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            input.previousOutput()
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    2_000L,

                                    /*
                                     * OP_0.
                                     *
                                     * Legacy script leaves false
                                     * on the stack.
                                     */
                                    new byte[]{0x00},

                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    @Test
    void shouldAcceptCoinbaseWithoutUtxoLookup() {

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                0x01,
                                                0x01
                                        },
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        5_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        UtxoView utxoView =
                requestedOutPoint -> {
                    throw new AssertionError(
                            "Coinbase validation must not access UTXO view"
                    );
                };

        assertDoesNotThrow(
                () -> TransactionValidator.validate(
                        transaction,
                        utxoView,
                        ScriptVerifyFlags.NONE
                )
        );
    }
    private static Transaction transaction(
            List<TxIn> inputs,
            List<TxOut> outputs
    ) {
        return new Transaction(
                1,
                inputs,
                outputs,
                new UInt32(0)
        );
    }
    private static TxIn input(
            String byteValue,
            long outputIndex
    ) {
        return new TxIn(
                new OutPoint(
                        Hash256.fromDisplayHex(
                                byteValue.repeat(32)
                        ),
                        new UInt32(
                                outputIndex
                        )
                ),
                new byte[0],
                TxIn.FINAL_SEQUENCE
        );
    }
    @Test
    void shouldValidateInputScriptsDuringContextualValidation() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "66".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        9_000L
                );

        UtxoView utxoView =
                outPoint -> {

                    if (!outPoint.equals(
                            previousOutput
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    10_000L,

                                    /*
                                     * OP_0.
                                     *
                                     * Script evaluation must fail.
                                     */
                                    new byte[]{0x00},

                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () ->
                        ContextualTransactionValidator.validate(
                                transaction,
                                200L,
                                utxoView,
                                ScriptVerifyFlags.NONE
                        )
        );
    }
    @Test
    void shouldAcceptValidScriptsDuringContextualValidation() {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "77".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction transaction =
                transaction(
                        previousOutput,
                        9_000L
                );

        UtxoView utxoView =
                outPoint -> {

                    if (!outPoint.equals(
                            previousOutput
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    10_000L,
                                    new byte[]{0x51},
                                    100L,
                                    false
                            )
                    );
                };

        TransactionContextResult result =
                ContextualTransactionValidator.validate(
                        transaction,
                        200L,
                        utxoView,
                        ScriptVerifyFlags.NONE
                );

        assertEquals(
                1_000L,
                result.fee()
        );
    }
}
