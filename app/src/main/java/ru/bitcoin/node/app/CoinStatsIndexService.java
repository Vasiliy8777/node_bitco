package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.MuHash3072;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.script.UnspendableScript;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.coinstats.RocksDbCoinStatsIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.CoreCoinStatsSerializer;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.Objects;
import java.util.Optional;

/**
 * Synchronizes the optional persistent coinstatsindex with the active chain.
 */
final class CoinStatsIndexService {
    private final RocksDbCoinStatsIndexStore store;
    private final RocksDbBlockStore blocks;
    private final RocksDbUndoStore undos;
    private final BlockIndexLookup lookup;
    private final ActiveChainAncestors ancestors;

    CoinStatsIndexService(RocksDbCoinStatsIndexStore store, RocksDbBlockStore blocks,
                          RocksDbUndoStore undos, BlockIndexLookup lookup, ActiveChainAncestors ancestors) {
        this.store = Objects.requireNonNull(store);
        this.blocks = Objects.requireNonNull(blocks);
        this.undos = Objects.requireNonNull(undos);
        this.lookup = Objects.requireNonNull(lookup);
        this.ancestors = Objects.requireNonNull(ancestors);
    }

    Optional<RocksDbCoinStatsIndexStore.CoinStats> find(Hash256 hash) {
        return store.find(hash);
    }

    void synchronize(BlockIndex activeTip) {
        var state = store.activeState().orElse(null);
        if (state == null) {
            BlockIndex genesis = ancestors.at(activeTip, 0L, lookup);
            if (genesis == null) throw new IllegalStateException("Cannot initialize coinstatsindex without genesis");
            store.initialize(genesis.hash());
            state = store.activeState().orElseThrow();
        }
        BlockIndex indexed = lookup.find(state.stats().blockHash());
        if (indexed == null) throw new IllegalStateException("coinstatsindex cursor references unknown block");
        ReorganizationPlan plan = ReorganizationPlanner.plan(indexed, activeTip, lookup);
        for (BlockIndex disconnect : plan.blocksToDisconnect()) disconnect(disconnect);
        for (BlockIndex connect : plan.blocksToConnect()) connect(connect);
        var end = store.activeState().orElseThrow().stats();
        if (!end.blockHash().equals(activeTip.hash()))
            throw new IllegalStateException("coinstatsindex synchronization stopped before active tip");
    }

    private void connect(BlockIndex index) {
        Block block = requireBlock(index);
        BlockUndoData undo = index.height() == 0 ? new BlockUndoData(java.util.List.of()) : requireUndo(index);
        var active = store.activeState().orElseThrow();
        MutableStats stats = new MutableStats(active.stats());
        MuHash3072 muhash = active.muhash();
        applyForward(block, undo, index.height(), stats, muhash);
        commit(index, stats, muhash);
    }

    private void disconnect(BlockIndex index) {
        if (index.height() == 0) throw new IllegalStateException("Cannot disconnect genesis from coinstatsindex");
        Block block = requireBlock(index);
        BlockUndoData undo = requireUndo(index);
        var active = store.activeState().orElseThrow();
        if (!active.stats().blockHash().equals(index.hash()))
            throw new IllegalStateException("coinstatsindex disconnect cursor mismatch");
        MutableStats stats = new MutableStats(active.stats());
        MuHash3072 muhash = active.muhash();
        applyReverse(block, undo, index.height(), stats, muhash);
        BlockIndex parent = lookup.find(index.previousBlockHash());
        if (parent == null) throw new IllegalStateException("Missing coinstatsindex parent");
        commit(parent, stats, muhash);
    }

    private void applyForward(Block block, BlockUndoData undo, long height, MutableStats stats, MuHash3072 muhash) {
        int undoIndex = 0;
        for (Transaction tx : block.transactions()) {
            if (!tx.isCoinbase()) {
                var txUndo = undo.transactions().get(undoIndex++);
                if (txUndo.spentOutputs().size() != tx.inputs().size())
                    throw new IllegalStateException("Coinstats undo/input mismatch");
                for (int i = 0; i < tx.inputs().size(); i++) {
                    var prevout = tx.inputs().get(i).previousOutput();
                    StoredUtxo spent = txUndo.spentOutputs().get(i);
                    muhash.remove(CoreCoinStatsSerializer.serialize(prevout.transactionId().bytes(), prevout.outputIndex().value(), spent));
                    decrementCount(prevout.transactionId(), stats);
                    stats.txouts--;
                    stats.totalAmount = Math.subtractExact(stats.totalAmount, spent.amount());
                    stats.bogoSize = Math.subtractExact(stats.bogoSize, 50L + spent.scriptPubKey().length);
                }
            }
            addOutputs(tx, height, stats, muhash);
        }
        if (undoIndex != undo.transactions().size()) throw new IllegalStateException("Unused coinstats undo entries");
    }

    private void applyReverse(Block block, BlockUndoData undo, long height, MutableStats stats, MuHash3072 muhash) {
        for (int txIndex = block.transactions().size() - 1; txIndex >= 0; txIndex--) {
            Transaction tx = block.transactions().get(txIndex);
            removeOutputs(tx, height, stats, muhash);
            if (!tx.isCoinbase()) {
                var txUndo = undo.transactions().get(txIndex - 1);
                for (int i = tx.inputs().size() - 1; i >= 0; i--) {
                    var prevout = tx.inputs().get(i).previousOutput();
                    StoredUtxo spent = txUndo.spentOutputs().get(i);
                    muhash.insert(CoreCoinStatsSerializer.serialize(prevout.transactionId().bytes(), prevout.outputIndex().value(), spent));
                    incrementCount(prevout.transactionId(), stats);
                    stats.txouts++;
                    stats.totalAmount = Math.addExact(stats.totalAmount, spent.amount());
                    stats.bogoSize = Math.addExact(stats.bogoSize, 50L + spent.scriptPubKey().length);
                }
            }
        }
    }

    private void addOutputs(Transaction tx, long height, MutableStats stats, MuHash3072 muhash) {
        for (int vout = 0; vout < tx.outputs().size(); vout++) {
            var output = tx.outputs().get(vout);
            byte[] script = output.scriptPubKey();
            if (UnspendableScript.isUnspendable(script)) continue;
            StoredUtxo coin = new StoredUtxo(output.value(), script, height, tx.isCoinbase());
            muhash.insert(CoreCoinStatsSerializer.serialize(tx.txId().bytes(), vout, coin));
            incrementCount(tx.txId(), stats);
            stats.txouts++;
            stats.totalAmount = Math.addExact(stats.totalAmount, output.value());
            stats.bogoSize = Math.addExact(stats.bogoSize, 50L + script.length);
        }
    }

    private void removeOutputs(Transaction tx, long height, MutableStats stats, MuHash3072 muhash) {
        for (int vout = tx.outputs().size() - 1; vout >= 0; vout--) {
            var output = tx.outputs().get(vout);
            byte[] script = output.scriptPubKey();
            if (UnspendableScript.isUnspendable(script)) continue;
            StoredUtxo coin = new StoredUtxo(output.value(), script, height, tx.isCoinbase());
            muhash.remove(CoreCoinStatsSerializer.serialize(tx.txId().bytes(), vout, coin));
            decrementCount(tx.txId(), stats);
            stats.txouts--;
            stats.totalAmount = Math.subtractExact(stats.totalAmount, output.value());
            stats.bogoSize = Math.subtractExact(stats.bogoSize, 50L + script.length);
        }
    }

    private void incrementCount(Hash256 txid, MutableStats stats) {
        int old = effectiveCount(txid);
        pendingCounts.put(txid, old + 1);
        if (old == 0) stats.transactions++;
    }

    private void decrementCount(Hash256 txid, MutableStats stats) {
        int old = effectiveCount(txid);
        if (old <= 0) throw new IllegalStateException("coinstatsindex output-count underflow");
        pendingCounts.put(txid, old - 1);
        if (old == 1) stats.transactions--;
    }

    private final java.util.LinkedHashMap<Hash256, Integer> pendingCounts = new java.util.LinkedHashMap<>();

    private int effectiveCount(Hash256 txid) {
        return pendingCounts.getOrDefault(txid, store.liveOutputCount(txid));
    }

    private void commit(BlockIndex index, MutableStats stats, MuHash3072 muhash) {
        if (stats.transactions < 0 || stats.txouts < 0 || stats.bogoSize < 0 || stats.totalAmount < 0)
            throw new IllegalStateException("coinstatsindex statistics underflow");
        var record = new RocksDbCoinStatsIndexStore.CoinStats(index.height(), index.hash(), stats.transactions,
                stats.txouts, stats.bogoSize, stats.totalAmount, muhash.finalizeHash());
        try (var batch = new RocksDbWriteBatch()) {
            for (var entry : pendingCounts.entrySet())
                store.setLiveOutputCount(batch, entry.getKey(), entry.getValue());
            store.commit(record, muhash, batch);
        }
        pendingCounts.clear();
    }

    private Block requireBlock(BlockIndex index) {
        return blocks.find(index.hash()).orElseThrow(() -> new IllegalStateException("Block body required by coinstatsindex: " + index.hash().toDisplayHex()));
    }

    private BlockUndoData requireUndo(BlockIndex index) {
        return undos.find(index.hash()).orElseThrow(() -> new IllegalStateException("Undo data required by coinstatsindex: " + index.hash().toDisplayHex()));
    }

    private static final class MutableStats {
        long transactions, txouts, bogoSize, totalAmount;

        MutableStats(RocksDbCoinStatsIndexStore.CoinStats stats) {
            transactions = stats.transactions();
            txouts = stats.txouts();
            bogoSize = stats.bogoSize();
            totalAmount = stats.totalAmount();
        }
    }
}
