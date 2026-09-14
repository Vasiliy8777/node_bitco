package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.HashSet;
import java.util.Set;

public final class TransactionValidator {

    /*
     * 21,000,000 BTC * 100,000,000 satoshi.
     */
    /*public static final long MAX_MONEY =
            21_000_000L * 100_000_000L;*/

    /*
     * Bitcoin Core checks non-witness serialized transaction size
     * against MAX_BLOCK_WEIGHT.
     *
     * We will add the exact serialized-size check immediately after
     * wiring the existing serializer API.
     */
    public static final int MAX_BLOCK_WEIGHT =
            4_000_000;

    public static final int WITNESS_SCALE_FACTOR =
            4;

    private TransactionValidator() {
    }

    public static void validateBasic(
            Transaction transaction
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (transaction.inputs().isEmpty()) {
            throw new TransactionValidationException(
                    "Transaction must contain at least one input"
            );
        }

        if (transaction.outputs().isEmpty()) {
            throw new TransactionValidationException(
                    "Transaction must contain at least one output"
            );
        }

        validateSize(
                transaction
        );

        validateOutputs(
                transaction
        );

        validateDuplicateInputs(
                transaction
        );

        if (transaction.isCoinbase()) {
            validateCoinbase(
                    transaction
            );
        } else {
            validateNonCoinbase(
                    transaction
            );
        }
    }

    private static void validateSize(
            Transaction transaction
    ) {
        byte[] legacySerialization =
                TransactionSerializer.serializeLegacy(
                        transaction
                );

        long transactionWeight =
                (long) legacySerialization.length
                        * WITNESS_SCALE_FACTOR;

        if (transactionWeight
                > MAX_BLOCK_WEIGHT) {

            throw new TransactionValidationException(
                    "Transaction size exceeds maximum allowed size"
            );
        }
    }
    private static void validateOutputs(
            Transaction transaction
    ) {
        long total = 0L;

        for (TxOut output :
                transaction.outputs()) {

            long value =
                    output.value();

            if (value < 0) {
                throw new TransactionValidationException(
                        "Transaction output value must not be negative"
                );
            }

            if (value > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "Transaction output value exceeds MAX_MONEY"
                );
            }

            try {
                total = Math.addExact(
                        total,
                        value
                );
            } catch (ArithmeticException e) {
                throw new TransactionValidationException(
                        "Transaction output total overflow"
                );
            }

            if (total > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "Transaction output total exceeds MAX_MONEY"
                );
            }
        }
    }

    private static void validateDuplicateInputs(
            Transaction transaction
    ) {
        Set<OutPoint> seen =
                new HashSet<>();

        for (TxIn input :
                transaction.inputs()) {

            if (!seen.add(
                    input.previousOutput()
            )) {
                throw new TransactionValidationException(
                        "Transaction contains duplicate inputs"
                );
            }
        }
    }

    private static void validateCoinbase(
            Transaction transaction
    ) {
        /*
         * isCoinbase() alone is not enough for consensus sanity:
         * coinbase must consist of exactly one input.
         */
        if (transaction.inputs().size() != 1) {
            throw new TransactionValidationException(
                    "Coinbase transaction must contain exactly one input"
            );
        }

        int scriptLength =
                transaction.inputs()
                        .getFirst()
                        .scriptSig()
                        .length;

        if (scriptLength < 2
                || scriptLength > 100) {

            throw new TransactionValidationException(
                    "Coinbase scriptSig size must be between 2 and 100 bytes"
            );
        }
    }

    private static void validateNonCoinbase(
            Transaction transaction
    ) {
        for (TxIn input :
                transaction.inputs()) {

            if (input.previousOutput()
                    .isCoinbase()) {

                throw new TransactionValidationException(
                        "Non-coinbase transaction contains null previous output"
                );
            }
        }
    }

    public static void validate(
            Transaction transaction,
            UtxoView utxoView,
            int scriptVerifyFlags
    ) {
        /*
         * Сначала всегда выполняются проверки,
         * не зависящие от состояния UTXO.
         */
        validateBasic(
                transaction
        );

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        /*
         * Coinbase не тратит предыдущие UTXO.
         *
         * Для неё contextual input validation
         * здесь не выполняется.
         */
        if (transaction.isCoinbase()) {
            return;
        }

        long totalInputValue =
                calculateInputValue(
                        transaction,
                        utxoView
                );

        long totalOutputValue =
                calculateOutputValue(
                        transaction
                );

        /*
         * Обычная транзакция не может создать
         * больше bitcoin, чем она получила
         * из потраченных UTXO.
         *
         * Разница:
         *
         * totalInputValue - totalOutputValue
         *
         * является transaction fee.
         */
        if (totalInputValue < totalOutputValue) {
            throw new TransactionValidationException(
                    "Transaction spends more than its inputs"
            );
        }

        /*
         * После проверки существования UTXO
         * и денежных ограничений проверяем
         * scriptSig / scriptPubKey / witness
         * каждого входа.
         */
        InputScriptValidator.validateAll(
                transaction,
                utxoView,
                scriptVerifyFlags
        );
    }

    private static long calculateInputValue(
            Transaction transaction,
            UtxoView utxoView
    ) {
        long total =
                0L;

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

            long amount =
                    utxo.amount();

            if (amount < 0) {
                throw new TransactionValidationException(
                        "UTXO amount must not be negative"
                );
            }

            if (amount > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "UTXO amount exceeds MAX_MONEY"
                );
            }

            try {
                total =
                        Math.addExact(
                                total,
                                amount
                        );
            } catch (ArithmeticException e) {
                throw new TransactionValidationException(
                        "Transaction input total overflow"
                );
            }

            if (total > Money.MAX_MONEY) {
                throw new TransactionValidationException(
                        "Transaction input total exceeds MAX_MONEY"
                );
            }
        }

        return total;
    }
    private static long calculateOutputValue(
            Transaction transaction
    ) {
        long total =
                0L;

        for (TxOut output :
                transaction.outputs()) {

            try {
                total =
                        Math.addExact(
                                total,
                                output.value()
                        );
            } catch (ArithmeticException e) {
                throw new TransactionValidationException(
                        "Transaction output total overflow"
                );
            }
        }

        return total;
    }

}