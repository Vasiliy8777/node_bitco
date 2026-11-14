package ru.bitcoin.node.app.rpc;

// Core getblockstats formulas: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.consensus.block.Bip30;
import ru.bitcoin.node.consensus.money.BlockSubsidy;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.script.UnspendableScript;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import java.util.*;

/** Core getblockstats arithmetic over the block and its actual spent coins. */
final class BlockStatistics {
    private BlockStatistics() { }
    private record Rate(long value, long weight) { }
    static Map<String, Object> calculate(NodeValidationService.RpcBlockView view, long medianTime,
                                         NetworkParameters parameters, Set<String> selected) {
        var block = view.block().orElseThrow();
        long height = view.info().index().height();
        BlockUndoData undo = height == 0 ? new BlockUndoData(List.of()) : view.undo()
                .orElseThrow(() -> new RpcException(-1, "Undo data not available"));
        long inputs = 0, outputs = 0, totalOut = 0, totalFee = 0, totalSize = 0, totalWeight = 0;
        long swSize = 0, swWeight = 0, swTransactions = 0, actualOutputs = 0, nominalSize = 0, actualSize = 0;
        var fees = new ArrayList<Long>(); var sizes = new ArrayList<Long>(); var rates = new ArrayList<Rate>();
        boolean bip30Repeat = !Bip30.shouldEnforce(height, block.hash(), parameters);
        for (int i = 0; i < block.transactions().size(); i++) {
            var tx = block.transactions().get(i);
            outputs += tx.outputs().size();
            long valueOut = 0;
            for (var output : tx.outputs()) {
                valueOut += output.value();
                long size = outputSize(output.scriptPubKey().length);
                nominalSize += size;
                if (height != 0 && !(bip30Repeat && tx.isCoinbase()) && !UnspendableScript.isUnspendable(output.scriptPubKey())) {
                    actualOutputs++; actualSize += size;
                }
            }
            if (tx.isCoinbase()) continue;
            inputs += tx.inputs().size(); totalOut += valueOut;
            long size = TransactionSerializer.serializedSize(tx, true), weight = TransactionWeight.calculate(tx);
            sizes.add(size); totalSize += size; totalWeight += weight;
            if (tx.hasWitness()) { swTransactions++; swSize += size; swWeight += weight; }
            long valueIn = 0;
            for (var coin : undo.transactions().get(i - 1).spentOutputs()) {
                valueIn += coin.amount(); long previousSize = outputSize(coin.scriptPubKey().length);
                nominalSize -= previousSize; actualSize -= previousSize;
            }
            long fee = valueIn - valueOut;
            if (fee < 0 || fee > 2_100_000_000_000_000L) throw new IllegalStateException("Invalid block fee or undo data");
            fees.add(fee); totalFee += fee; rates.add(new Rate(weight == 0 ? 0 : fee * 4 / weight, weight));
        }
        var result = new TreeMap<String, Object>();
        long nonCoinbase = block.transactions().size() - 1;
        result.put("avgfee", nonCoinbase > 0 ? totalFee / nonCoinbase : 0L);
        result.put("avgfeerate", totalWeight == 0 ? 0L : totalFee * 4 / totalWeight);
        result.put("avgtxsize", nonCoinbase > 0 ? totalSize / nonCoinbase : 0L);
        result.put("blockhash", block.hash().toDisplayHex()); result.put("feerate_percentiles", percentiles(rates, totalWeight));
        result.put("height", height); result.put("ins", inputs); result.put("maxfee", maximum(fees));
        result.put("maxfeerate", rates.stream().mapToLong(Rate::value).max().orElse(0)); result.put("maxtxsize", maximum(sizes));
        result.put("medianfee", median(fees)); result.put("mediantime", medianTime); result.put("mediantxsize", median(sizes));
        result.put("minfee", minimum(fees)); result.put("minfeerate", rates.stream().mapToLong(Rate::value).min().orElse(0));
        result.put("mintxsize", minimum(sizes)); result.put("outs", outputs); result.put("subsidy", BlockSubsidy.calculate(height, parameters));
        result.put("swtotal_size", swSize); result.put("swtotal_weight", swWeight); result.put("swtxs", swTransactions);
        result.put("time", block.header().timestamp().value()); result.put("total_out", totalOut); result.put("total_size", totalSize);
        result.put("total_weight", totalWeight); result.put("totalfee", totalFee); result.put("txs", block.transactions().size());
        result.put("utxo_increase", outputs - inputs); result.put("utxo_size_inc", nominalSize);
        result.put("utxo_increase_actual", actualOutputs - inputs); result.put("utxo_size_inc_actual", actualSize);
        if (selected.isEmpty()) return result;
        var subset = new TreeMap<String, Object>();
        for (String key : selected) {
            if (!result.containsKey(key)) throw new RpcException(-8, "Invalid selected statistic '" + key + "'");
            subset.put(key, result.get(key));
        }
        return subset;
    }
    private static long outputSize(int scriptLength) { return 40L + 8 + CompactSize.encodedSize(scriptLength) + scriptLength; }
    private static long minimum(List<Long> values) { return values.stream().mapToLong(Long::longValue).min().orElse(0); }
    private static long maximum(List<Long> values) { return values.stream().mapToLong(Long::longValue).max().orElse(0); }
    private static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        values.sort(Long::compare); int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }
    private static List<Long> percentiles(List<Rate> rates, long totalWeight) {
        long[] result = new long[5];
        rates.sort(Comparator.comparingLong(Rate::value).thenComparingLong(Rate::weight));
        double[] targets = {totalWeight / 10.0, totalWeight / 4.0, totalWeight / 2.0, totalWeight * 3.0 / 4.0, totalWeight * 9.0 / 10.0};
        long cumulative = 0; int next = 0;
        for (Rate rate : rates) {
            cumulative += rate.weight();
            while (next < targets.length && cumulative >= targets[next]) result[next++] = rate.value();
        }
        return Arrays.stream(result).boxed().toList();
    }
}
