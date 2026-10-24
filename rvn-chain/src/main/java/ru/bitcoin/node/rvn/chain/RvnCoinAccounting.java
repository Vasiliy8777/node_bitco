package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;

/** Native-coin accounting only, without mutating the parent view. Does not check
 * scripts, finality/sequence locks, assets, deployments, headers or PoW.
 * Results must never be used as a full-block validation status.
 */
public final class RvnCoinAccounting {
    public static final long COINBASE_MATURITY = 100;
    private RvnCoinAccounting() {}

    /** Net changes relative to the parent. Spent entries retain coins for undo;
     * outputs created and consumed within the selection occur in neither map. */
    public record Changes(long fees, Map<OutPoint, RvnCoin> spent, Map<OutPoint, RvnCoin> created) {
        public Changes {
            spent = Map.copyOf(spent);
            created = Map.copyOf(created);
        }
    }

    public static Changes transactions(long height, List<Transaction> transactions, RvnCoinView parent) {
        return transactions(height, transactions, parent, (tx, index, coin) -> {});
    }

    @FunctionalInterface
    interface InputCheck { void requireValid(Transaction tx, int index, RvnCoin coin); }

    static Changes transactions(long height, List<Transaction> transactions, RvnCoinView parent, InputCheck check) {
        return transactions(height, transactions, parent, check, true);
    }

    /** Core GetValueOut(enforcedValues). Historical asset outputs are excluded
     * from fees before deployment; their original nValue still enters the UTXO.
     * This does not validate asset layouts or authorize historical transactions. */
    public static Changes transactions(long height, List<Transaction> transactions, RvnCoinView parent, boolean enforcedValues) {
        return transactions(height, transactions, parent, (tx, index, coin) -> {}, enforcedValues);
    }

    static Changes transactions(long height, List<Transaction> transactions, RvnCoinView parent, InputCheck check, boolean enforcedValues) {
        requireHeight(height);
        Objects.requireNonNull(parent);
        var selected = List.copyOf(transactions);
        Map<OutPoint, RvnCoin> spent = new HashMap<>();
        Map<OutPoint, RvnCoin> created = new HashMap<>();
        Set<OutPoint> consumed = new HashSet<>();
        Set<ru.bitcoin.node.common.types.Hash256> ids = new HashSet<>();
        long fees = 0;
        for (var tx : selected) {
            if (tx.isCoinbase() || tx.inputs().isEmpty() || tx.outputs().isEmpty())
                throw new SecurityException("RVN selection requires non-coinbase transactions with inputs/outputs");
            if (!ids.add(tx.txId())) throw new SecurityException("Duplicate RVN transaction ID");
            long inputValue = 0;
            for (int index = 0; index < tx.inputs().size(); index++) {
                var input = tx.inputs().get(index);
                var point = input.previousOutput();
                if (point.isCoinbase() || !consumed.add(point))
                    throw new SecurityException("Invalid or already spent RVN input");
                RvnCoin coin = created.remove(point);
                if (coin == null) {
                    coin = parent.find(point).orElseThrow(() -> new SecurityException("Missing RVN input coin"));
                    spent.put(point, coin);
                }
                if (coin.height() > height || (coin.coinbase() && height - coin.height() < COINBASE_MATURITY))
                    throw new SecurityException("Immature or future RVN input coin");
                inputValue = addMoney(inputValue, coin.output().value());
                check.requireValid(tx, index, coin);
            }
            long outputValue = outputValue(tx, enforcedValues);
            if (outputValue > inputValue) throw new SecurityException("RVN transaction spends more than inputs");
            fees = addMoney(fees, inputValue - outputValue);
            addOutputs(tx, height, false, parent, created);
        }
        return new Changes(fees, spent, created);
    }

    public static Changes block(RvnBlock block, long height, boolean assetsActive, RvnCoinView parent) {
        return block(block, height, assetsActive, parent, true);
    }

    public static Changes block(RvnBlock block, long height, boolean assetsActive, RvnCoinView parent, boolean enforcedValues) {
        requireHeight(height);
        RvnBlockBodyChecks.requireValid(block, assetsActive);
        if (block.header().isKawpow() && block.header().declaredHeight() != height)
            throw new SecurityException("RVN block height mismatch");
        var txs = block.transactions();
        // Coinbase outputs are deliberately unavailable while processing the body.
        var changes = transactions(height, txs.subList(1, txs.size()), parent, enforcedValues);
        long reward = addMoney(RvnCoinbase.subsidy(height), changes.fees());
        if (outputValue(txs.getFirst(), enforcedValues) > reward)
            throw new SecurityException("RVN coinbase exceeds subsidy and fees");
        var created = new HashMap<>(changes.created());
        addOutputs(txs.getFirst(), height, true, parent, created);
        return new Changes(changes.fees(), changes.spent(), created);
    }

    private static void addOutputs(Transaction tx, long height, boolean coinbase, RvnCoinView parent,
                                   Map<OutPoint, RvnCoin> created) {
        for (int index = 0; index < tx.outputs().size(); index++) {
            var point = new OutPoint(tx.txId(), new UInt32(index));
            if (created.containsKey(point) || parent.find(point).isPresent())
                throw new SecurityException("RVN output overwrites an unspent coin");
            var output = tx.outputs().get(index);
            // Match Core's unspendable-output pruning, including oversized scripts.
            byte[] script = output.scriptPubKey();
            if (RvnScriptSemantics.unspendable(script)) continue;
            created.put(point, new RvnCoin(output, height, coinbase));
        }
    }

    private static long outputValue(Transaction tx, boolean enforcedValues) {
        long total = 0, checkedTotal = 0;
        for (var output : tx.outputs()) {
            // CheckTransaction ranges every output even when old GetValueOut skips it.
            checkedTotal = addMoney(checkedTotal, output.value());
            if (!enforcedValues && ru.bitcoin.node.rvn.chain.assets.RvnHistoricalAssetCodec.classify(output.scriptPubKey()).isPresent())
                continue;
            total = addMoney(total, output.value());
        }
        return total;
    }

    private static long addMoney(long total, long value) {
        if (value < 0 || value > RvnBlockBodyChecks.MAX_MONEY || total > RvnBlockBodyChecks.MAX_MONEY - value)
            throw new SecurityException("RVN coin amount range");
        return total + value;
    }

    private static void requireHeight(long height) {
        if (height < 1 || height > 0xffff_ffffL) throw new IllegalArgumentException("RVN spending height");
    }
}
