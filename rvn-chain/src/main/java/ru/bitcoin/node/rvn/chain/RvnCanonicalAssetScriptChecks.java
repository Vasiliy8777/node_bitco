package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.Transaction;

/** Compatibility entry point. The explicit RVN interpreter now executes original
 * scripts and retains the complete suffix for signature hashing. Asset wire and
 * state validity are separate checks in the block validator. */
public final class RvnCanonicalAssetScriptChecks {
    private RvnCanonicalAssetScriptChecks() {}
    public static void requireValid(Transaction tx, int index, RvnCoin coin, int flags) {
        RvnNativeScriptChecks.requireValid(tx, index, coin, flags);
    }
}
