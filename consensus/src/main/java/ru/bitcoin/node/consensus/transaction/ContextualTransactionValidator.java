package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.script.ScriptVerifyFlags;

public final class ContextualTransactionValidator {
    public static final long COINBASE_MATURITY =
            100L;
    private ContextualTransactionValidator() {
    }

    public static TransactionContextResult validate(
            Transaction transaction,
            long spendingHeight,
            UtxoView utxoView
    ) {
        return validate(
                transaction,
                spendingHeight,
                utxoView,
                ScriptVerifyFlags.NONE
        );
    }
    public static TransactionContextResult validate(
            Transaction transaction,
            long spendingHeight,
            UtxoView utxoView,
            int scriptVerifyFlags
    ) {
        TransactionContextResult result = validateInputs(transaction, spendingHeight, utxoView);
        InputScriptValidator.validateAll(transaction, utxoView, scriptVerifyFlags);
        return result;
    }

    /** Checks structure, amounts and maturity. Does not execute input scripts. */
    public static TransactionContextResult validateInputs(
            Transaction transaction, long spendingHeight, UtxoView utxoView
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (spendingHeight < 0) {
            throw new IllegalArgumentException(
                    "spendingHeight must not be negative"
            );
        }

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        TransactionValidator.validateBasic(
                transaction
        );

        if (transaction.isCoinbase()) {
            throw new IllegalArgumentException(
                    "Coinbase transaction has no UTXO inputs"
            );
        }

        long inputValue = 0L;

        for (TxIn input :
                transaction.inputs()) {

            UtxoEntry utxo =
                    utxoView.find(
                                    input.previousOutput()
                            )
                            .orElseThrow(
                                    () ->
                                            new TransactionValidationException(
                                                    "Missing or already spent UTXO: "
                                                            + input.previousOutput()
                                            )
                            );

            validateCoinbaseMaturity(
                    utxo,
                    spendingHeight
            );

            try {
                inputValue =
                        Math.addExact(
                                inputValue,
                                utxo.amount()
                        );
            } catch (ArithmeticException e) {
                throw new TransactionValidationException(
                        "Transaction input value overflow", "bad-txns-inputvalues-outofrange", null
                );
            }

            if (inputValue > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "Transaction input value exceeds MAX_MONEY", "bad-txns-inputvalues-outofrange", null
                );
            }
        }

        long outputValue = 0L;

        for (TxOut output :
                transaction.outputs()) {

            try {
                outputValue =
                        Math.addExact(
                                outputValue,
                                output.value()
                        );
            } catch (ArithmeticException e) {
                throw new TransactionValidationException(
                        "Transaction output value overflow"
                );
            }

            if (outputValue > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "Transaction output value exceeds MAX_MONEY"
                );
            }
        }

        if (inputValue < outputValue) {
            throw new TransactionValidationException(
                    "Transaction spends more than its inputs", "bad-txns-in-belowout",
                    "value in (" + money(inputValue) + ") < value out (" + money(outputValue) + ")"
            );
        }

        long fee =
                inputValue - outputValue;

        return new TransactionContextResult(
                inputValue,
                outputValue,
                fee
        );
    }
    private static void validateCoinbaseMaturity(
            UtxoEntry utxo,
            long spendingHeight
    ) {
        if (!utxo.coinbase()) {
            return;
        }

        long confirmations =
                spendingHeight - utxo.height();

        if (confirmations
                < COINBASE_MATURITY) {

            throw new TransactionValidationException(
                    "Premature spend of coinbase UTXO: "
                            + "created at height "
                            + utxo.height()
                            + ", spending height "
                            + spendingHeight, "bad-txns-premature-spend-of-coinbase",
                    "tried to spend coinbase at depth " + confirmations
            );
        }
    }
    private static String money(long value) {
        var amount=java.math.BigDecimal.valueOf(value,8).stripTrailingZeros();
        return amount.setScale(Math.max(2,amount.scale())).toPlainString();
    }
}
