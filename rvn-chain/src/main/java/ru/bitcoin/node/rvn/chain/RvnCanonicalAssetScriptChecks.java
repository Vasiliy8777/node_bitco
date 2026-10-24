package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec;
import ru.bitcoin.node.script.*;

/** Canonical P2PKH asset spending only. Core's OP_RVN_ASSET consumes the entire
 * suffix as a no-op. Execute the checked prefix but retain the FULL original
 * scriptCode for CHECKSIG; stripping the suffix from signature hashing is wrong. */
public final class RvnCanonicalAssetScriptChecks {
    private RvnCanonicalAssetScriptChecks() {}
    public static void requireValid(Transaction tx, int index, RvnCoin coin, int flags) {
        RvnNativeScriptChecks.requireFlags(flags);
        byte[] full = coin.output().scriptPubKey();
        var asset = RvnAssetScriptCodec.decodeCanonical(full);
        if (asset.isEmpty()) {
            RvnNativeScriptChecks.requireValid(tx, index, coin, flags);
            return;
        }
        if (tx.isCoinbase() || !tx.inputs().get(index).witness().isEmpty())
            throw new SecurityException("Unexpected witness/coinbase for canonical asset spend");
        var machine = new ScriptMachine();
        byte[] sig = tx.inputs().get(index).scriptSig();
        try {
            ScriptInterpreter.execute(sig, machine, new ScriptExecutionContext(tx, index, sig, flags));
            ScriptInterpreter.execute(asset.get().lockingScript(), machine, new ScriptExecutionContext(tx, index, full, flags));
            if (machine.isEmpty() || !ScriptNumber.castToBool(machine.peek()))
                throw new SecurityException("RVN asset input signature failed");
        } catch (ScriptExecutionException e) { throw new SecurityException("RVN asset script execution failed", e); }
    }
}
