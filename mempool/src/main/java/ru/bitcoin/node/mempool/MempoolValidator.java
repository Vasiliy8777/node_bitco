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
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(utxoView, "utxoView");
        if (transaction.isCoinbase()) throw new MempoolAdmissionException("Coinbase cannot enter mempool");
        var result = ContextualTransactionValidator.validateInputs(transaction, context.nextBlockHeight(), utxoView);
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
            throw new MempoolAdmissionException("Non-final BIP68 sequence locks");
        }
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateInputs(transaction, utxoView);
        InputScriptValidator.validateAll(transaction, utxoView, StandardScriptVerifyFlags.STANDARD);
        return result.fee();
    }
}
