package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.chain.RocksDbReindexStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize;

/**
 * Restart-safe rebuild of derived active-chain state from persisted block bodies/indexes.
 * Block bodies, indexes, best-header knowledge and permanent failure markers are preserved.
 */
public final class ChainstateReindexer {

    private final RocksDbDatabase database;
    private final NetworkParameters parameters;
    private final AdjustedTime adjustedTime;

    public ChainstateReindexer(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.adjustedTime = Objects.requireNonNull(adjustedTime, "adjustedTime");
    }

    public boolean isInProgress() {
        synchronized (database) {
            return new RocksDbReindexStateStore(database).loadTargetTipHash().isPresent();
        }
    }

    public Result rebuild() {
        synchronized (database) {
            if (database.isEmpty()) {
                ChainState initialized = new ChainInitializer(database, parameters).initialize();
                return new Result(initialized.activeTip().hash(), initialized.activeTip().height(), 0L);
            }

            var blocks = new RocksDbBlockStore(database);
            var indexes = new RocksDbBlockIndexStore(database);
            var tips = new RocksDbChainStateStore(database);
            var utxos = new RocksDbUtxoStore(database);
            var undos = new RocksDbUndoStore(database);
            var availability = new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database);
            var failures = new RocksDbBlockFailureStore(database);
            var marker = new RocksDbReindexStateStore(database);
            BlockIndexLookup lookup = new StoredBlockIndexLookup(indexes);
            var failureResolver = new BlockFailureResolver(lookup, failures);

            Block genesis = GenesisBlockFactory.create(parameters);
            BlockIndex expectedGenesis = BlockIndexFactory.createGenesis(genesis.header());
            BlockIndex storedGenesis = indexes.find(genesis.hash())
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot reindex chainstate: genesis BlockIndex is missing"));
            Block storedGenesisBody = blocks.find(genesis.hash())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot reindex chainstate: genesis block body is missing"));
            if (!BlockIndexStorageMapper.toStored(storedGenesis)
                    .equals(BlockIndexStorageMapper.toStored(expectedGenesis))
                    || !Arrays.equals(serialize(genesis), serialize(storedGenesisBody))) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: stored genesis does not match selected network");
            }

            var markerTarget = marker.loadTargetTipHash();
            var activeTarget = tips.loadActiveTipHash();
            var targetHash = markerTarget.or(() -> activeTarget)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot reindex chainstate: active tip is missing and no recovery marker exists"));
            BlockIndex target = requireIndex(indexes, targetHash,
                    "Cannot reindex chainstate: target BlockIndex is missing: ");

            if (failureResolver.isFailed(target)) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: target belongs to a permanently failed chain: "
                                + target.hash().toDisplayHex());
            }

            List<BlockIndex> activePath = activePath(storedGenesis, target, lookup);
            preflightBodies(activePath, blocks);

            // Header sync may legitimately be ahead of the active block chain. Preserve that knowledge.
            var bestHeaderHash = tips.loadBestHeaderTipHash().orElse(target.hash());
            BlockIndex bestHeader = requireIndex(indexes, bestHeaderHash,
                    "Cannot reindex chainstate: best-header BlockIndex is missing: ");
            if (failureResolver.isFailed(bestHeader)) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: persisted best header belongs to a failed chain: "
                                + bestHeader.hash().toDisplayHex());
            }

            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                utxos.clear(batch);
                undos.clear(batch);
                // The undo namespace is being rebuilt from scratch. Keep HAVE_DATA intact,
                // but clear every durable HAVE_UNDO bit in the same reset transaction.
                for (var stored : indexes.findAll()) {
                    availability.clearUndo(batch, stored.hash());
                }
                tips.clear(batch);
                marker.saveTargetTipHash(batch, target.hash());
                tips.saveActiveTipHash(batch, storedGenesis.hash());
                tips.saveBestHeaderTipHash(batch, bestHeader.hash());
                database.write(batch);
            }

            ChainState chainState = new ChainState(storedGenesis);
            var failureManager = new BlockFailureManager(
                    database, failures, indexes, tips, failureResolver);
            var transitionStorage = new RocksDbChainTransitionStorage(
                    database, utxos, undos, indexes, tips);
            var executor = new ChainReorganizationExecutor(
                    blocks,
                    undos,
                    utxos,
                    new ChainTransitionManager(chainState, transitionStorage),
                    parameters,
                    lookup,
                    failureManager::markFailed
            );
            var processor = new BlockProcessor(
                    chainState,
                    lookup,
                    new KnownBlockStorage(database, blocks, indexes),
                    executor,
                    parameters,
                    adjustedTime,
                    failureManager,
                    failureResolver
            );

            long replayed = 0L;
            for (int i = 1; i < activePath.size(); i++) {
                BlockIndex expected = activePath.get(i);
                Block block = blocks.find(expected.hash())
                        .orElseThrow(() -> new IllegalStateException(
                                "Block body disappeared during chainstate reindex: "
                                        + expected.hash().toDisplayHex()));
                BlockProcessingResult result = processor.process(block);
                if (result != BlockProcessingResult.CONNECTED) {
                    throw new IllegalStateException(
                            "Unexpected chainstate replay result for "
                                    + expected.hash().toDisplayHex() + ": " + result);
                }
                replayed++;
            }

            if (!chainState.activeTip().hash().equals(target.hash())) {
                throw new IllegalStateException(
                        "Chainstate reindex finished at unexpected tip: "
                                + chainState.activeTip().hash().toDisplayHex());
            }

            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                marker.clear(batch);
                database.write(batch);
            }

            return new Result(target.hash(), target.height(), replayed);
        }
    }

    private static BlockIndex requireIndex(
            RocksDbBlockIndexStore indexes,
            ru.bitcoin.node.common.types.Hash256 hash,
            String message
    ) {
        return indexes.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(message + hash.toDisplayHex()));
    }

    private static List<BlockIndex> activePath(
            BlockIndex genesis,
            BlockIndex target,
            BlockIndexLookup lookup
    ) {
        ArrayDeque<BlockIndex> reversed = new ArrayDeque<>();
        BlockIndex cursor = target;
        while (true) {
            if (!cursor.hash().equals(cursor.header().hash())
                    || !cursor.previousBlockHash().equals(cursor.header().previousBlockHash())) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: inconsistent stored BlockIndex at height "
                                + cursor.height());
            }
            reversed.addFirst(cursor);
            if (cursor.height() == 0) break;
            BlockIndex parent = lookup.find(cursor.previousBlockHash());
            if (parent == null || parent.height() != cursor.height() - 1) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: missing or inconsistent ancestor of "
                                + cursor.hash().toDisplayHex());
            }
            BlockIndex expected = BlockIndexFactory.createChild(parent, cursor.header());
            if (!expected.chainWork().equals(cursor.chainWork())) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: inconsistent accumulated chain work at "
                                + cursor.hash().toDisplayHex());
            }
            cursor = parent;
        }
        if (!cursor.equals(genesis)) {
            throw new IllegalStateException(
                    "Cannot reindex chainstate: active chain does not terminate at selected genesis");
        }
        return List.copyOf(new ArrayList<>(reversed));
    }

    private static void preflightBodies(List<BlockIndex> path, RocksDbBlockStore blocks) {
        for (BlockIndex index : path) {
            Block block = blocks.find(index.hash())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot reindex chainstate: missing block body at height "
                                    + index.height() + ": " + index.hash().toDisplayHex()));
            if (!block.hash().equals(index.hash())) {
                throw new IllegalStateException(
                        "Cannot reindex chainstate: block body/index hash mismatch at height "
                                + index.height());
            }
        }
    }

    public record Result(
            ru.bitcoin.node.common.types.Hash256 tipHash,
            long height,
            long replayedBlocks
    ) {
    }
}
