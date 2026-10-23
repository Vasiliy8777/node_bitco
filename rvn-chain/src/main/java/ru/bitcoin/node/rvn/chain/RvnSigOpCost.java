package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;

/** RVN native signature operation costs. Units are legacy sigops * 4 plus
 * witness sigops. The block limit remains 80000 after RIP2's weight increase. */
public final class RvnSigOpCost {
    public static final long MAX_BLOCK_COST = 80_000;
    private RvnSigOpCost() {}

    public static long legacy(Transaction tx) {
        long count = 0;
        for (var input : tx.inputs()) count += SigOpCounter.count(input.scriptSig(), false);
        for (var output : tx.outputs()) count += SigOpCounter.count(output.scriptPubKey(), false);
        return count * 4;
    }

    public static long input(Transaction tx, int index, RvnCoin coin, int flags) {
        RvnNativeScriptChecks.requireFlags(flags);
        var input = tx.inputs().get(index);
        byte[] script = coin.output().scriptPubKey();
        byte[] redeem = ScriptVerifyFlags.has(flags, ScriptVerifyFlags.P2SH) && P2shScript.isPayToScriptHash(script)
                ? SigOpCounter.lastPush(input.scriptSig()) : null;
        long cost = redeem == null ? 0 : 4 * SigOpCounter.count(redeem, true);
        if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)) {
            cost += witness(script, input.witness());
            if (redeem != null) cost += witness(redeem, input.witness());
        }
        return cost;
    }

    private static long witness(byte[] script, Witness witness) {
        var program = WitnessProgram.parse(script);
        if (program.isEmpty() || program.get().version() != 0) return 0;
        if (program.get().isP2wpkh()) return 1;
        if (program.get().isP2wsh() && !witness.isEmpty())
            return SigOpCounter.count(witness.item(witness.size() - 1), true);
        return 0;
    }
}
