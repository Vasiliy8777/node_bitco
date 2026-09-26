package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
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
            var graph = scanAndValidateGraph(blocks);
            Block genesis = GenesisBlockFactory.create(parameters);
            Block storedGenesis = blocks.find(genesis.hash()).orElseThrow(() ->
                    new IllegalStateException("Cannot reindex: selected-network genesis block body is missing"));
            if (!Arrays.equals(serialize(genesis), serialize(storedGenesis))) {
                throw new IllegalStateException("Cannot reindex: stored genesis does not match selected network");
            }
            if (!graph.headers.containsKey(genesis.hash())) {
                throw new IllegalStateException("Cannot reindex: genesis header is missing from block-body namespace");
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
                long activeHeight = heightBeforeReset(expectedActive, indexes, graph, genesis.hash());
                long bestHeaderHeight = heightBeforeReset(expectedBestHeader, indexes, graph, genesis.hash());
                recoveryManifest = new RocksDbFullReindexStateStore.Manifest(
                        expectedActive, activeHeight, expectedBestHeader, bestHeaderHeight);
            }
            if (recoveryManifest != null && !graph.headers.containsKey(recoveryManifest.expectedActiveTipHash())) {
                throw new IllegalStateException("Cannot reindex: expected active tip body is missing: "
                        + recoveryManifest.expectedActiveTipHash().toDisplayHex());
            }

            var utxos = new RocksDbUtxoStore(database);
            var undos = new RocksDbUndoStore(database);
            var failures = new RocksDbBlockFailureStore(database);
            var chainstateMarker = new RocksDbReindexStateStore(database);
            var txIndex = new RocksDbTxIndexStore(database);
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

            ArrayDeque<Hash256> queue = new ArrayDeque<>();
            queue.add(genesis.hash());
            Set<Hash256> failedBranches = new HashSet<>();
            long replayed = 0;
            long skippedFailed = 0;

            while (!queue.isEmpty()) {
                Hash256 parentHash = queue.removeFirst();
                for (Hash256 hash : graph.children.getOrDefault(parentHash, List.of())) {
                    if (failures.isFailed(hash) || failedBranches.contains(parentHash)) {
                        failedBranches.add(hash);
                        skippedFailed++;
                        queue.addLast(hash);
                        continue;
                    }
                    Block block = blocks.find(hash).orElseThrow(() ->
                            new IllegalStateException("Block body disappeared during full reindex: " + hash.toDisplayHex()));
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
                    queue.addLast(hash);
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
                database.write(batch);
            }

            return new Result(chainState.activeTip().hash(), chainState.activeTip().height(),
                    bestHeader.hash(), bestHeader.height(), replayed, skippedFailed, graph.headers.size());
        }
    }


    private long heightBeforeReset(Hash256 hash, RocksDbBlockIndexStore indexes, Graph graph, Hash256 genesisHash) {
        return indexes.find(hash).map(ru.bitcoin.node.storage.block.StoredBlockIndex::height)
                .orElseGet(() -> heightInRawGraph(hash, graph, genesisHash));
    }

    private long heightInRawGraph(Hash256 hash, Graph graph, Hash256 genesisHash) {
        long height = 0;
        Hash256 cursor = hash;
        Set<Hash256> seen = new HashSet<>();
        while (!cursor.equals(genesisHash)) {
            if (!seen.add(cursor)) throw new IllegalStateException("Cannot reindex: cycle while resolving expected tip height");
            BlockHeader header = graph.headers.get(cursor);
            if (header == null) {
                throw new IllegalStateException("Cannot reindex: expected tip is absent from both block index and raw bodies: "
                        + hash.toDisplayHex());
            }
            cursor = header.previousBlockHash();
            height++;
        }
        return height;
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

    private Graph scanAndValidateGraph(RocksDbBlockStore blocks) {
        Map<Hash256, BlockHeader> headers = new HashMap<>();
        blocks.forEachHeader(header -> {
            Hash256 hash = header.hash();
            BlockHeader previous = headers.putIfAbsent(hash, header);
            if (previous != null && !previous.equals(header)) {
                throw new IllegalStateException("Conflicting persisted block headers for " + hash.toDisplayHex());
            }
        });
        if (headers.isEmpty()) {
            throw new IllegalStateException("Cannot reindex: no persisted block bodies");
        }

        Hash256 genesisHash = GenesisBlockFactory.create(parameters).hash();
        Map<Hash256, List<Hash256>> children = new HashMap<>();
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equals(genesisHash)) continue;
            Hash256 parent = entry.getValue().previousBlockHash();
            if (!headers.containsKey(parent)) {
                throw new IllegalStateException("Cannot reindex: block " + entry.getKey().toDisplayHex()
                        + " has missing raw parent " + parent.toDisplayHex());
            }
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry.getKey());
        }
        for (List<Hash256> hashes : children.values()) {
            hashes.sort(Comparator.comparing(Hash256::toDisplayHex));
        }

        // Prove every stored body is reachable from the selected genesis before destructive reset.
        Set<Hash256> reachable = new HashSet<>();
        ArrayDeque<Hash256> queue = new ArrayDeque<>();
        queue.add(genesisHash);
        while (!queue.isEmpty()) {
            Hash256 hash = queue.removeFirst();
            if (!reachable.add(hash)) continue;
            queue.addAll(children.getOrDefault(hash, List.of()));
        }
        if (reachable.size() != headers.size()) {
            throw new IllegalStateException("Cannot reindex: raw block graph contains a cycle or a chain not rooted at selected genesis");
        }
        return new Graph(Map.copyOf(headers), immutableChildren(children));
    }

    private static Map<Hash256, List<Hash256>> immutableChildren(Map<Hash256, List<Hash256>> children) {
        Map<Hash256, List<Hash256>> copy = new HashMap<>();
        children.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private record Graph(Map<Hash256, BlockHeader> headers, Map<Hash256, List<Hash256>> children) {
    }

    public record Result(Hash256 activeTipHash, long activeHeight,
                         Hash256 bestHeaderHash, long bestHeaderHeight,
                         long replayedBlocks, long skippedFailedBlocks, long discoveredBlockBodies) {
    }
}
