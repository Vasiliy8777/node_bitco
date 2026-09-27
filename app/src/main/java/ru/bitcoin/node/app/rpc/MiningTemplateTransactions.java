package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.money.BlockSubsidy;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;

import java.util.*;

/** Validates and renders the transaction portion of a BIP22 getblocktemplate snapshot. */
final class MiningTemplateTransactions {
    private MiningTemplateTransactions() { }

    static List<Map<String, Object>> build(Block block, List<MempoolEntry> snapshot,
                                           long height, NetworkParameters parameters) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(parameters, "parameters");
        if (block.transactions().isEmpty() || !block.transactions().getFirst().isCoinbase())
            throw new IllegalStateException("Mining template must start with coinbase");

        Map<Hash256, MempoolEntry> entries = new HashMap<>();
        for (MempoolEntry entry : snapshot) {
            Hash256 txid = entry.transaction().txId();
            if (entries.put(txid, entry) != null)
                throw new IllegalStateException("Duplicate txid in mining mempool snapshot: " + txid.toDisplayHex());
        }

        List<ru.bitcoin.node.protocol.transaction.Transaction> selected =
                block.transactions().subList(1, block.transactions().size());
        Map<Hash256, Integer> positions = new HashMap<>();
        for (int i = 0; i < selected.size(); i++) {
            Hash256 txid = selected.get(i).txId();
            if (positions.put(txid, i + 1) != null)
                throw new IllegalStateException("Duplicate transaction in mining block: " + txid.toDisplayHex());
        }

        long fees = 0;
        List<Map<String, Object>> result = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            var tx = selected.get(i);
            Hash256 txid = tx.txId();
            MempoolEntry entry = entries.get(txid);
            if (entry == null)
                throw new IllegalStateException("Mining block transaction is absent from mempool snapshot: "
                        + txid.toDisplayHex());
            if (!Arrays.equals(TransactionSerializer.serialize(tx),
                    TransactionSerializer.serialize(entry.transaction())))
                throw new IllegalStateException("Mining block transaction does not match mempool snapshot: "
                        + txid.toDisplayHex());

            long actualWeight = TransactionWeight.calculate(tx);
            if (entry.weight() != actualWeight)
                throw new IllegalStateException("Stale mining weight metadata for " + txid.toDisplayHex());

            int currentPosition = i + 1;
            TreeSet<Integer> depends = new TreeSet<>();
            for (var input : tx.inputs()) {
                Integer parentPosition = positions.get(input.previousOutput().transactionId());
                if (parentPosition == null) continue;
                if (parentPosition >= currentPosition)
                    throw new IllegalStateException("Mining transactions are not parent-before-child: "
                            + txid.toDisplayHex());
                depends.add(parentPosition);
            }

            fees = Math.addExact(fees, entry.fee());
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("data", HexFormat.of().formatHex(TransactionSerializer.serialize(tx)));
            rendered.put("txid", txid.toDisplayHex());
            rendered.put("hash", tx.wtxId().toDisplayHex());
            rendered.put("depends", List.copyOf(depends));
            rendered.put("fee", entry.fee());
            rendered.put("sigops", entry.sigOpCost());
            rendered.put("weight", actualWeight);
            result.add(Collections.unmodifiableMap(rendered));
        }

        long coinbaseValue = 0;
        for (var output : block.transactions().getFirst().outputs())
            coinbaseValue = Math.addExact(coinbaseValue, output.value());
        long expected = Math.addExact(BlockSubsidy.calculate(height, parameters), fees);
        if (coinbaseValue != expected)
            throw new IllegalStateException("Mining coinbase value does not equal subsidy plus selected fees");

        return List.copyOf(result);
    }
}
