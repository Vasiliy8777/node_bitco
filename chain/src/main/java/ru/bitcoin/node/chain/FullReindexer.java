package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.consensus.block.BlockValidationException;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.script.ScriptExecutionException;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.chain.RocksDbFullReindexStateStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.chain.RocksDbReindexStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.txindex.RocksDbTxIndexStore;
import ru.bitcoin.node.storage.txospender.RocksDbTxOutSpenderIndexStore;

import java.util.*;

import static ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize;

/**
 * Restart-safe full rebuild of block indexes and chainstate from persisted raw block bodies.
 * Unlike ChainstateReindexer this intentionally discards the block-index namespaces first.
 * Raw block bodies and permanent invalid-block markers are preserved.
 */
public final class FullReindexer {
    private final RocksDbDatabase database;
    private final NetworkParameters parameters;
    private final AdjustedTime adjustedTime;

    public FullReindexer(RocksDbDatabase database, NetworkParameters parameters, AdjustedTime adjustedTime) {
        this.database = Objects.requireNonNull(database, "database");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.adjustedTime = Objects.requireNonNull(adjustedTime, "adjustedTime");
    }

    public boolean isInProgress() {
        synchronized (database) {
            return new RocksDbFullReindexStateStore(database).isInProgress();
        }
    }

    public Result rebuild() {
        synchronized (database) {
            var pruneState = new RocksDbPruneStateStore(database);
            if (pruneState.hasPruned()) {
                throw new IllegalStateException(
                        "bitcoin.reindex cannot rebuild from locally pruned block data; historical blocks must be restored/redownloaded");
            }

            var blocks = new RocksDbBlockStore(database);
            Block genesis = GenesisBlockFactory.create(parameters);
            var graph = new RawBlockReindexGraph(database);
            long discoveredBlockBodies = graph.build(blocks, genesis.hash());
            Block storedGenesis = blocks.find(genesis.hash()).orElseThrow(() ->
                    new IllegalStateException("Cannot reindex: selected-network genesis block body is missing"));
            if (!Arrays.equals(serialize(genesis), serialize(storedGenesis))) {
                throw new IllegalStateException("Cannot reindex: stored genesis does not match selected network");
            }

            var indexes = new RocksDbBlockIndexStore(database);
            var tips = new RocksDbChainStateStore(database);
            var marker = new RocksDbFullReindexStateStore(database);

            // Preserve the pre-reindex target before the destructive reset. If this is a
            // restart of a manifest-aware interrupted rebuild, the original target wins
            // over the temporary genesis tips written by the reset transaction.
            RocksDbFullReindexStateStore.Manifest recoveryManifest = marker.manifest().orElse(null);
            if (recoveryManifest == null && !marker.isInProgress()) {
                Hash256 expectedActive = tips.loadActiveTipHash().orElseThrow(() ->
                        new IllegalStateException("Cannot reindex: active tip metadata is missing"));
                Hash256 expectedBestHeader = tips.loadBestHeaderTipHash().orElseThrow(() ->
                        new IllegalStateException("Cannot reindex: best-header metadata is missing"));
                long activeHeight = heightBeforeReset(expectedActive, indexes, graph);
                long bestHeaderHeight = heightBeforeReset(expectedBestHeader, indexes, graph);
                recoveryManifest = new RocksDbFullReindexStateStore.Manifest(
                        expectedActive, activeHeight, expectedBestHeader, bestHeaderHeight);
            }
            if (recoveryManifest != null && !graph.contains(recoveryManifest.expectedActiveTipHash())) {
                throw new IllegalStateException("Cannot reindex: expected active tip body is missing: "
                        + recoveryManifest.expectedActiveTipHash().toDisplayHex());
            }

            var utxos = new RocksDbUtxoStore(database);
            var undos = new RocksDbUndoStore(database);
            var failures = new RocksDbBlockFailureStore(database);
            var chainstateMarker = new RocksDbReindexStateStore(database);
            var txIndex = new RocksDbTxIndexStore(database);
            var txOutSpenderIndex = new RocksDbTxOutSpenderIndexStore(database);
            var availability = new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database);
            var validationStatus = new ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore(database);
            var validationMigration = new ru.bitcoin.node.storage.block.RocksDbBlockValidationMigrationStore(database);
            BlockIndex genesisIndex = BlockIndexFactory.createGenesis(genesis.header());

            // One durable reset point. If power is lost after this commit, the marker makes
            // startup repeat the rebuild from the same immutable raw-block namespace.
            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                indexes.clear(batch);
                utxos.clear(batch);
                undos.clear(batch);
                tips.clear(batch);
                // A full block-index rebuild invalidates every txindex mapping/cursor as well.
                // Clear it in the same durable reset so a crash can never expose a stale cursor
                // that references the pre-reindex block-index namespace. When txindex is enabled,
                // NodeValidationService rebuilds it from genesis to the reconstructed active tip.
                txIndex.clear(batch);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.TXO_SPENDER_INDEX);
                batch.delete(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.singletonKey(
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.TXO_SPENDER_INDEX_STATE));
                availability.clear(batch);
                validationStatus.clear(batch);
                validationMigration.clear(batch);
                chainstateMarker.clear(batch);
                if (recoveryManifest != null) marker.markInProgress(batch, recoveryManifest);
                else marker.markInProgress(batch); // resume of a legacy one-byte marker
                indexes.save(batch, BlockIndexStorageMapper.toStored(genesisIndex));
                availability.markData(batch, genesisIndex.hash());
                validationStatus.markScriptsValid(batch, genesisIndex.hash());
                tips.saveActiveTipHash(batch, genesisIndex.hash());
                tips.saveBestHeaderTipHash(batch, genesisIndex.hash());
                database.write(batch);
            }

            BlockIndexLookup lookup = new StoredBlockIndexLookup(indexes);
            var failureResolver = new BlockFailureResolver(lookup, failures);
            var failureManager = new BlockFailureManager(database, failures, indexes, tips, failureResolver);
            ChainState chainState = new ChainState(genesisIndex);
            var transitionStorage = new RocksDbChainTransitionStorage(database, utxos, undos, indexes, tips);
            var executor = new ChainReorganizationExecutor(
                    blocks, undos, utxos,
                    new ChainTransitionManager(chainState, transitionStorage),
                    parameters, lookup, failureManager::markFailed);
            var processor = new BlockProcessor(
                    chainState, lookup, new KnownBlockStorage(database, blocks, indexes), executor,
                    parameters, adjustedTime, failureManager, failureResolver);

            long replayed = 0;
            long skippedFailed = 0;

            // Sequence zero is genesis, which the reset transaction already materialized.
            // The remaining sequence is a disk-backed topological order built during preflight.
            for (long sequence = 1; sequence < discoveredBlockBodies; sequence++) {
                Hash256 hash = graph.hashAt(sequence);
                Block block = blocks.find(hash).orElseThrow(() ->
                        new IllegalStateException("Block body disappeared during full reindex: " + hash.toDisplayHex()));
                Hash256 parentHash = block.header().previousBlockHash();
                if (failures.isFailed(hash)) {
                    graph.markFailedBranch(hash);
                    skippedFailed++;
                    continue;
                }
                if (graph.isFailedBranch(parentHash)) {
                    /*
                     * The parent is already known to descend from a consensus-invalid root.
                     * Do not contextually validate/connect this block, but still rebuild the
                     * deterministic BlockIndex/HAVE_DATA metadata for every persisted raw body.
                     * The descendant itself is not a new permanent failure root.
                     */
                    materializeFailedDescendant(block, indexes, availability);
                    graph.markFailedBranch(hash);
                    skippedFailed++;
                    continue;
                }
                try {
                    BlockProcessingResult result = processor.process(block);
                    if (result == BlockProcessingResult.UNKNOWN_PARENT) {
                        throw new IllegalStateException("Topological full reindex produced UNKNOWN_PARENT for "
                                + hash.toDisplayHex());
                    }
                    if (result == BlockProcessingResult.ALREADY_IN_ACTIVE_CHAIN) {
                        throw new IllegalStateException("Unexpected duplicate active block during full reindex: "
                                + hash.toDisplayHex());
                    }
                    replayed++;
                } catch (BlockHeaderValidationException
                         | BlockValidationException
                         | TransactionValidationException
                         | ScriptExecutionException invalid) {
                    /*
                     * A raw side-chain body is not proof that it was ever contextually valid.
                     * Consensus-invalid branches are persisted as failed and replay continues;
                     * storage/invariant failures deliberately escape and abort the rebuild.
                     */
                    Set<Hash256> discoveredRoots = committedFailureRoots(hash, indexes, failures);
                    if (discoveredRoots.isEmpty()) {
                        persistEarlyRejectedBlock(block, indexes, failures, availability);
                    }
                    graph.markFailedBranch(hash);
                    skippedFailed++;
                }
            }

            BlockIndex bestHeader = indexes.findBest(stored ->
                            !failureResolver.isFailed(BlockIndexStorageMapper.fromStored(stored)))
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException("No eligible BlockIndex after full reindex"));

            verifyRecoveryTarget(recoveryManifest, chainState.activeTip());

            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                tips.saveBestHeaderTipHash(batch, bestHeader.hash());
                marker.clear(batch);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.FULL_REINDEX_RAW_MEMBERSHIP);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.FULL_REINDEX_RAW_EDGE);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.FULL_REINDEX_RAW_QUEUE);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.FULL_REINDEX_RAW_HEIGHT);
                batch.deletePrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.FULL_REINDEX_FAILED_BRANCH);
                database.write(batch);
            }

            return new Result(chainState.activeTip().hash(), chainState.activeTip().height(),
                    bestHeader.hash(), bestHeader.height(), replayed, skippedFailed, discoveredBlockBodies);
        }
    }


    private Set<Hash256> committedFailureRoots(
            Hash256 hash,
            RocksDbBlockIndexStore indexes,
            RocksDbBlockFailureStore failures
    ) {
        var stored = indexes.find(hash);
        if (stored.isEmpty()) return Set.of();

        Set<Hash256> roots = new HashSet<>();
        BlockIndex cursor = BlockIndexStorageMapper.fromStored(stored.orElseThrow());
        Set<Hash256> visited = new HashSet<>();
        while (true) {
            if (!visited.add(cursor.hash())) {
                throw new IllegalStateException("Cycle while resolving replay failure at "
                        + cursor.hash().toDisplayHex());
            }
            if (failures.isFailed(cursor.hash())) roots.add(cursor.hash());
            if (cursor.height() == 0) break;
            Hash256 previous = cursor.previousBlockHash();
            cursor = indexes.find(previous)
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing BlockIndex ancestor while resolving replay failure: "
                                    + previous.toDisplayHex()));
        }
        return Set.copyOf(roots);
    }

    private void materializeFailedDescendant(
            Block block,
            RocksDbBlockIndexStore indexes,
            ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore availability
    ) {
        Hash256 parentHash = block.header().previousBlockHash();
        BlockIndex parent = indexes.find(parentHash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing parent BlockIndex for failed-branch raw block: "
                                + parentHash.toDisplayHex()));
        BlockIndex descendant = BlockIndexFactory.createChild(parent, block.header());
        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            indexes.save(batch, BlockIndexStorageMapper.toStored(descendant));
            availability.markData(batch, descendant.hash());
            database.write(batch);
        }
    }

    private void persistEarlyRejectedBlock(
            Block block,
            RocksDbBlockIndexStore indexes,
            RocksDbBlockFailureStore failures,
            ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore availability
    ) {
        Hash256 parentHash = block.header().previousBlockHash();
        BlockIndex parent = indexes.find(parentHash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing parent BlockIndex for rejected raw block: " + parentHash.toDisplayHex()));
        BlockIndex rejected = BlockIndexFactory.createChild(parent, block.header());
        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            indexes.save(batch, BlockIndexStorageMapper.toStored(rejected));
            availability.markData(batch, rejected.hash());
            failures.markFailed(batch, rejected.hash());
            database.write(batch);
        }
    }

    private long heightBeforeReset(Hash256 hash, RocksDbBlockIndexStore indexes, RawBlockReindexGraph graph) {
        return indexes.find(hash).map(ru.bitcoin.node.storage.block.StoredBlockIndex::height)
                .orElseGet(() -> graph.height(hash));
    }

    private void verifyRecoveryTarget(RocksDbFullReindexStateStore.Manifest manifest, BlockIndex activeTip) {
        if (manifest == null) return; // legacy interrupted rebuild had no recoverable target metadata
        if (!activeTip.hash().equals(manifest.expectedActiveTipHash())
                || activeTip.height() != manifest.expectedActiveTipHeight()) {
            throw new IllegalStateException("Full reindex reconstructed a different active tip. Expected "
                    + manifest.expectedActiveTipHash().toDisplayHex() + " at height "
                    + manifest.expectedActiveTipHeight() + ", got " + activeTip.hash().toDisplayHex()
                    + " at height " + activeTip.height());
        }
        // expectedBestHeader* is diagnostic recovery metadata only. A full reindex rebuilds
        // the header index from the complete persisted raw-body graph and may legitimately
        // discover a stronger header than a stale/corrupted pre-reindex best-header pointer.
        // Requiring equality here would turn index repair into a false corruption failure.
    }

    public record Result(Hash256 activeTipHash, long activeHeight,
                         Hash256 bestHeaderHash, long bestHeaderHeight,
                         long replayedBlocks, long skippedFailedBlocks, long discoveredBlockBodies) {
    }
}
