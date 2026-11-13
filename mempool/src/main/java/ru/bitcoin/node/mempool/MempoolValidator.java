package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.ArrayList;
import java.util.Objects;

public final class MempoolValidator {
    private MempoolValidator() { }

    /** Standard next-block admission, including BIP68 and BIP113. Returns the fee. */
    public static long validate(Transaction transaction, MempoolValidationContext context, UtxoView utxoView) {
        long fee = validatePreChecks(transaction, context, utxoView);
        validateScripts(transaction, utxoView);
        return fee;
    }

    /** Contextual/input policy without executing scripts; used before fee/RBF checks. */
    static long validatePreChecks(Transaction transaction, MempoolValidationContext context, UtxoView utxoView) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(utxoView, "utxoView");
        if (transaction.isCoinbase()) throw new MempoolAdmissionException("Coinbase cannot enter mempool");
        TransactionFinality.validate(transaction, context.nextBlockHeight(), context.tipMedianTimePast());
        var confirmations = new ArrayList<InputConfirmation>();
        for (var input : transaction.inputs()) {
            var coin = utxoView.find(input.previousOutput()).orElseThrow();
            if (coin.height() > context.nextBlockHeight()) {
                throw new MempoolAdmissionException("Input confirmation is above next-block height");
            }
            long time = 0;
            long sequence = input.sequence().value();
            if (Integer.toUnsignedLong(transaction.version()) >= 2
                    && (sequence & SequenceLocks.SEQUENCE_LOCKTIME_DISABLE_FLAG) == 0
                    && (sequence & SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG) != 0) {
                time = coin.height() == context.nextBlockHeight() ? context.tipMedianTimePast()
                        : context.coinPreviousMedianTimePast().applyAsLong(coin.height());
            }
            confirmations.add(new InputConfirmation(coin.height(), time));
        }
        if (!SequenceLocks.evaluate(SequenceLocks.calculate(transaction, confirmations),
                context.nextBlockHeight(), context.tipMedianTimePast())) {
            throw new MempoolPolicyException("Non-final BIP68 sequence locks","non-BIP68-final",null);
        }
        var result = ContextualTransactionValidator.validateInputs(transaction, context.nextBlockHeight(), utxoView);
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateInputs(transaction, utxoView, false);
        return result.fee();
    }

    static void validateScripts(Transaction transaction, UtxoView utxoView) {
        try { InputScriptValidator.validateAll(transaction, utxoView, StandardScriptVerifyFlags.STANDARD, true); }
        catch (InputScriptValidationException error) {
            throw new ru.bitcoin.node.script.ScriptVerificationException(error.code(),
                    failure(transaction, error.inputIndex(), error.description()).details());
        }
    }

    record ScriptFailure(String reason, String details) {}

    /** Ordered input diagnostics for a non-mutating probe. Never catches infrastructure errors. */
    static ScriptFailure testScripts(Transaction transaction, UtxoView utxoView) {
        for (int i = 0; i < transaction.inputs().size(); i++) {
            try {
                InputScriptValidator.validate(transaction, i, utxoView, StandardScriptVerifyFlags.STANDARD, true);
            } catch (ru.bitcoin.node.script.ScriptExecutionException
                     | ru.bitcoin.node.script.ScriptParseException | TransactionValidationException error) {
                String description = error instanceof ru.bitcoin.node.script.ScriptExecutionException script ? script.error()
                        : error instanceof ru.bitcoin.node.script.ScriptParseException parse ? parse.error() : error.getMessage();
                return failure(transaction, i, description);
            }
        }
        return null;
    }
    private static ScriptFailure failure(Transaction transaction, int inputIndex, String description) {
        String reason = "mempool-script-verify-flag-failed (" + description + ")";
        var prevout = transaction.inputs().get(inputIndex).previousOutput();
        String debug = "input " + inputIndex + " of " + transaction.txId().toDisplayHex()
                + " (wtxid " + transaction.wtxId().toDisplayHex() + "), spending "
                + prevout.transactionId().toDisplayHex() + ":" + prevout.outputIndex().value();
        return new ScriptFailure(reason, reason + ", " + debug);
    }
}
