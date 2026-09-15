package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.*;

import java.util.Objects;

public final class TransactionSigOpCost {
    private TransactionSigOpCost() { }

    public static long legacyCost(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        long count = 0;
        for (var input : transaction.inputs()) count += SigOpCounter.count(input.scriptSig(), false);
        for (var output : transaction.outputs()) count += SigOpCounter.count(output.scriptPubKey(), false);
        return count * 4;
    }

    public static long calculate(Transaction transaction, UtxoView view, int flags) {
        Objects.requireNonNull(view, "view");
        long cost = legacyCost(transaction);
        if (transaction.isCoinbase()) return cost;
        for (var input : transaction.inputs()) {
            var coin = view.find(input.previousOutput()).orElseThrow(
                    () -> new TransactionValidationException("Missing UTXO for sigops accounting"));
            byte[] script = coin.scriptPubKey();
            boolean p2sh = ScriptVerifyFlags.has(flags, ScriptVerifyFlags.P2SH)
                    && P2shScript.isPayToScriptHash(script);
            byte[] redeem = p2sh ? SigOpCounter.lastPush(input.scriptSig()) : null;
            if (redeem != null) cost += 4 * SigOpCounter.count(redeem, true);
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)) {
                cost += witnessCost(script, input.witness());
                if (redeem != null) cost += witnessCost(redeem, input.witness());
            }
        }
        return cost;
    }

    private static long witnessCost(byte[] script, Witness witness) {
        var program = WitnessProgram.parse(script);
        if (program.isEmpty() || program.get().version() != 0) return 0;
        if (program.get().isP2wpkh()) return 1;
        if (program.get().isP2wsh() && !witness.isEmpty()) {
            return SigOpCounter.count(witness.item(witness.size() - 1), true);
        }
        return 0;
    }
}
