package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.*;
import java.util.List;

/** Native accounting, scripts, locktime and sigop checks. Contextual overloads
 * also check BIP68. Does NOT validate assets, deployments, headers or PoW
 * and must not advance a validated block tip. */
public final class RvnNativeTransactionChecks {
    private RvnNativeTransactionChecks() {}

    public static RvnCoinAccounting.Changes transactions(long height, long lockTimeCutoff,
            List<Transaction> transactions, RvnCoinView parent, int scriptFlags) {
        return transactions(new RvnSpendContext(height, lockTimeCutoff, 0, false, scriptFlags,
                ignored -> { throw new IllegalStateException("No RVN branch MTP supplied"); }), transactions, parent);
    }

    public static RvnCoinAccounting.Changes transactions(RvnSpendContext context,
            List<Transaction> transactions, RvnCoinView parent) {
        return check(context, transactions, parent, 0);
    }

    public static RvnCoinAccounting.Changes block(RvnBlock block, RvnSpendContext context,
            boolean assetsActive, RvnCoinView parent) {
        return block(block, context, assetsActive, parent, false, assetsActive);
    }

    /** Native part of the explicit canonical-asset validator; not general asset admission. */
    public static RvnCoinAccounting.Changes blockCanonicalAssets(RvnBlock block, RvnSpendContext context, RvnCoinView parent) {
        return blockCanonicalAssets(block, context, parent, true);
    }

    public static RvnCoinAccounting.Changes blockCanonicalAssets(RvnBlock block, RvnSpendContext context,
            RvnCoinView parent, boolean enforcedValues) {
        return block(block, context, true, parent, true, enforcedValues);
    }

    private static RvnCoinAccounting.Changes block(RvnBlock block, RvnSpendContext context,
            boolean assetsActive, RvnCoinView parent, boolean canonicalAssets, boolean enforcedValues) {
        if (!assetsActive) for (var tx : block.transactions())
            ru.bitcoin.node.rvn.chain.assets.RvnCanonicalAssetBlockChecks.requireBeforeAssets(tx);
        var changes = RvnCoinAccounting.block(block, context.height(), assetsActive, parent, enforcedValues);
        var txs = block.transactions();
        check(context, txs.subList(1, txs.size()), parent, RvnSigOpCost.legacy(txs.getFirst()), canonicalAssets, enforcedValues);
        requireFinal(txs.getFirst(), context);
        return changes;
    }

    private static RvnCoinAccounting.Changes check(RvnSpendContext context,
            List<Transaction> transactions, RvnCoinView parent, long initialSigops) {
        return check(context, transactions, parent, initialSigops, false, true);
    }

    private static RvnCoinAccounting.Changes check(RvnSpendContext context,
            List<Transaction> transactions, RvnCoinView parent, long initialSigops, boolean canonicalAssets, boolean enforcedValues) {
        var selected = List.copyOf(transactions);
        long[] sigops = {initialSigops};
        for (var tx : selected) {
            requireFinal(tx, context);
            sigops[0] = Math.addExact(sigops[0], RvnSigOpCost.legacy(tx));
        }
        requireSigops(sigops[0]);
        return RvnCoinAccounting.transactions(context.height(), selected, parent, (tx, index, coin) -> {
            requireSequence(tx, index, coin, context);
            sigops[0] = Math.addExact(sigops[0], RvnSigOpCost.input(tx, index, coin, context.scriptFlags()));
            requireSigops(sigops[0]);
            if (canonicalAssets) RvnCanonicalAssetScriptChecks.requireValid(tx, index, coin, context.scriptFlags());
            else RvnNativeScriptChecks.requireValid(tx, index, coin, context.scriptFlags());
        }, enforcedValues);
    }

    private static void requireFinal(Transaction tx, RvnSpendContext context) {
        long lock = tx.lockTime().value();
        long cutoff = lock < 500_000_000L ? context.height() : context.lockTimeCutoff();
        if (lock != 0 && lock >= cutoff
                && tx.inputs().stream().anyMatch(in -> !in.sequence().equals(TxIn.FINAL_SEQUENCE)))
            throw new SecurityException("Non-final RVN transaction");
    }

    private static void requireSequence(Transaction tx, int index, RvnCoin coin, RvnSpendContext context) {
        long sequence = tx.inputs().get(index).sequence().value();
        if (!context.sequenceLocks() || Integer.toUnsignedLong(tx.version()) < 2 || (sequence & (1L << 31)) != 0) return;
        long relative = sequence & 0xffff;
        if ((sequence & (1L << 22)) == 0) {
            if (context.height() < coin.height() + relative)
                throw new SecurityException("RVN relative height lock not satisfied");
        } else {
            // An output created earlier in this candidate uses the parent's MTP.
            long mtp = coin.height() == context.height() ? context.parentMedianTimePast()
                    : context.medianTimePastAtHeight().applyAsLong(Math.max(0, coin.height() - 1));
            if (mtp < 0 || mtp > context.parentMedianTimePast())
                throw new SecurityException("Invalid RVN coin ancestor MTP");
            if (context.parentMedianTimePast() < mtp + (relative << 9))
                throw new SecurityException("RVN relative time lock not satisfied");
        }
    }

    private static void requireSigops(long cost) {
        if (cost > RvnSigOpCost.MAX_BLOCK_COST) throw new SecurityException("RVN block sigop cost limit");
    }
}
