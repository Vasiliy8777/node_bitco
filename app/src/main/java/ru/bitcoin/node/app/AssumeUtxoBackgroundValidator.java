package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.utxo.BlockReorganizationChangesBuilder;
import ru.bitcoin.node.chain.utxo.BlockToConnect;
import ru.bitcoin.node.consensus.transaction.LockTimeCutoff;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.rocksdb.*;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.*;

import java.util.List;

/** Incremental, restart-safe full validation of the historical chainstate up to the snapshot base. */
final class AssumeUtxoBackgroundValidator {
    enum Step { INACTIVE, WAITING_FOR_BLOCK, ADVANCED, VALIDATED, INVALID }

    private final RocksDbDatabase db;
    private final NetworkParameters params;
    private final BlockIndexLookup lookup;
    private final RocksDbBlockStore blocks;
    private final RocksDbUtxoStore normalUtxos;
    private final RocksDbUndoStore undos;
    private final RocksDbBlockAvailabilityStore availability;
    private final RocksDbBlockValidationStatusStore validationStatus;
    private final RocksDbSnapshotChainStateStore snapshotState;
    private final RocksDbAssumeUtxoBackgroundStore progress;

    AssumeUtxoBackgroundValidator(RocksDbDatabase db, NetworkParameters params, BlockIndexLookup lookup) {
        this.db=db; this.params=params; this.lookup=lookup;
        blocks=new RocksDbBlockStore(db); normalUtxos=new RocksDbUtxoStore(db, RocksDbNamespaces.UTXO);
        undos=new RocksDbUndoStore(db); availability=new RocksDbBlockAvailabilityStore(db);
        validationStatus=new RocksDbBlockValidationStatusStore(db);
        snapshotState=new RocksDbSnapshotChainStateStore(db); progress=new RocksDbAssumeUtxoBackgroundStore(db);
    }

    void initializeIfNeeded() {
        snapshotState.load().ifPresent(s -> progress.initialize(s.normalTipHash(), s.normalTipHeight()));
    }

    RocksDbAssumeUtxoBackgroundStore.State state() { return progress.load().orElse(null); }

    Step step() {
        var snap = snapshotState.load().orElse(null);
        if (snap == null) return Step.INACTIVE;
        var state = progress.load().orElseGet(() -> {
            progress.initialize(snap.normalTipHash(), snap.normalTipHeight());
            return progress.load().orElseThrow();
        });
        if (state.status() == RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED) return Step.VALIDATED;
        if (state.status() == RocksDbAssumeUtxoBackgroundStore.Status.INVALID) return Step.INVALID;
        if (state.tipHeight() == snap.snapshotBaseHeight()) return finalizeAtBase(snap, state);
        if (state.tipHeight() > snap.snapshotBaseHeight()) return invalidate(state);

        BlockIndex current = lookup.find(state.tipHash());
        BlockIndex target = lookup.find(snap.snapshotBaseHash());
        if (current == null || target == null) return invalidate(state);
        BlockIndex next = ancestor(target, current.height()+1);
        if (next == null || !next.previousBlockHash().equals(current.hash())) return invalidate(state);
        Block block = blocks.find(next.hash()).orElse(null);
        if (block == null) return Step.WAITING_FOR_BLOCK;

        long prevMtp = MedianTimePast.calculate(current, lookup);
        long cutoff = LockTimeCutoff.calculate(next.height(), block.header().timestamp().value(), prevMtp, params);
        final ru.bitcoin.node.chain.utxo.BlockReorganizationChanges changes;
        try {
            changes = BlockReorganizationChangesBuilder.build(List.of(),
                    List.of(new BlockToConnect(block, next.height(), cutoff, prevMtp)), normalUtxos, params, lookup);
        } catch (ru.bitcoin.node.consensus.block.BlockValidationException
                 | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                 | ru.bitcoin.node.script.ScriptExecutionException
                 | ru.bitcoin.node.script.ScriptParseException e) {
            return invalidate(state);
        }
        try (var batch = new RocksDbWriteBatch()) {
            for (var created : changes.utxoChanges().createdOutputs()) normalUtxos.save(batch, created.outPoint(), created.utxo());
            for (var spent : changes.utxoChanges().spentOutputs()) normalUtxos.delete(batch, spent);
            for (var e : changes.connectedBlockUndo().entrySet()) {
                undos.save(batch, e.getKey(), e.getValue());
                availability.markUndo(batch, e.getKey());
                validationStatus.markScriptsValid(batch, e.getKey());
            }
            progress.save(batch, new RocksDbAssumeUtxoBackgroundStore.State(
                    RocksDbAssumeUtxoBackgroundStore.Status.RUNNING, next.hash(), next.height()));
            db.write(batch);
        }
        return next.height() == snap.snapshotBaseHeight()
                ? finalizeAtBase(snap, progress.load().orElseThrow()) : Step.ADVANCED;
    }

    private Step finalizeAtBase(RocksDbSnapshotChainStateStore.State snap, RocksDbAssumeUtxoBackgroundStore.State state) {
        var trusted = params.assumeUtxoForBlock(snap.snapshotBaseHash()).orElse(null);
        if (trusted == null) return invalidate(state);
        var stats = normalUtxos.statistics(RocksDbUtxoStore.HashType.HASH_SERIALIZED_3);
        if (stats.hashSerialized3() == null || !stats.hashSerialized3().equals(trusted.hashSerialized())) return invalidate(state);
        progress.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, state.tipHash(), state.tipHeight());
        return Step.VALIDATED;
    }

    private Step invalidate(RocksDbAssumeUtxoBackgroundStore.State state) {
        progress.mark(RocksDbAssumeUtxoBackgroundStore.Status.INVALID, state.tipHash(), state.tipHeight());
        return Step.INVALID;
    }

    private BlockIndex ancestor(BlockIndex tip, long height) {
        BlockIndex c=tip;
        while(c!=null && c.height()>height) c=lookup.find(c.previousBlockHash());
        return c!=null && c.height()==height ? c : null;
    }
}
