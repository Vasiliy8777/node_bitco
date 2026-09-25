package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.Arrays;
import java.util.Objects;

import static ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize;

/**
 * Startup integrity check for persistent chain metadata and the reorg safety window.
 *
 * The full active/header ancestry is verified from block-index metadata. Raw block bodies
 * and undo data are required only for the configured recent active-chain depth, which keeps
 * this check compatible with future pruning while still guaranteeing that every block that
 * may need to be disconnected during an ordinary reorganization is locally available.
 */
public final class ChainstateConsistencyChecker {
    public static final int DEFAULT_REORG_SAFETY_DEPTH = 288;

    private final RocksDbDatabase database;
    private final NetworkParameters parameters;
    private final int recentDataDepth;

    public ChainstateConsistencyChecker(
            RocksDbDatabase database,
            NetworkParameters parameters
    ) {
        this(database, parameters, DEFAULT_REORG_SAFETY_DEPTH);
    }

    public ChainstateConsistencyChecker(
            RocksDbDatabase database,
            NetworkParameters parameters,
            int recentDataDepth
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        if (recentDataDepth < 0) {
            throw new IllegalArgumentException("recentDataDepth must not be negative");
        }
        this.recentDataDepth = recentDataDepth;
    }

    public Result verify() {
        synchronized (database) {
            if (database.isEmpty()) {
                return new Result(0L, 0L, 0L);
            }

            var indexes = new RocksDbBlockIndexStore(database);
            var blocks = new RocksDbBlockStore(database);
            var undos = new RocksDbUndoStore(database);
            var tips = new RocksDbChainStateStore(database);
            var lookup = new StoredBlockIndexLookup(indexes);

            Block genesis = GenesisBlockFactory.create(parameters);
            BlockIndex expectedGenesis = BlockIndexFactory.createGenesis(genesis.header());
            BlockIndex storedGenesis = indexes.find(genesis.hash())
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException(
                            "Startup consistency check failed: genesis BlockIndex is missing"));
            if (!BlockIndexStorageMapper.toStored(storedGenesis)
                    .equals(BlockIndexStorageMapper.toStored(expectedGenesis))) {
                throw new IllegalStateException(
                        "Startup consistency check failed: stored genesis index does not match selected network");
            }
            Block storedGenesisBody = blocks.find(genesis.hash())
                    .orElseThrow(() -> new IllegalStateException(
                            "Startup consistency check failed: genesis block body is missing"));
            if (!Arrays.equals(serialize(genesis), serialize(storedGenesisBody))) {
                throw new IllegalStateException(
                        "Startup consistency check failed: stored genesis body does not match selected network");
            }

            BlockIndex activeTip = requireTip(indexes, tips.loadActiveTipHash().orElseThrow(
                    () -> new IllegalStateException(
                            "Startup consistency check failed: active tip metadata is missing")), "active");
            BlockIndex bestHeaderTip = requireTip(indexes, tips.loadBestHeaderTipHash().orElseThrow(
                    () -> new IllegalStateException(
                            "Startup consistency check failed: best-header tip metadata is missing")), "best-header");

            long activeEntries = verifyAncestry(activeTip, storedGenesis, lookup, "active");
            long headerEntries = verifyAncestry(bestHeaderTip, storedGenesis, lookup, "best-header");

            long recentChecked = verifyRecentActiveData(activeTip, lookup, blocks, undos);
            return new Result(activeEntries, headerEntries, recentChecked);
        }
    }

    private BlockIndex requireTip(
            RocksDbBlockIndexStore indexes,
            ru.bitcoin.node.common.types.Hash256 hash,
            String kind
    ) {
        return indexes.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Startup consistency check failed: " + kind
                                + " tip BlockIndex is missing: " + hash.toDisplayHex()));
    }

    private static long verifyAncestry(
            BlockIndex tip,
            BlockIndex genesis,
            BlockIndexLookup lookup,
            String kind
    ) {
        BlockIndex cursor = tip;
        long checked = 1L;
        while (cursor.height() > 0) {
            if (!cursor.hash().equals(cursor.header().hash())
                    || !cursor.previousBlockHash().equals(cursor.header().previousBlockHash())) {
                throw new IllegalStateException(
                        "Startup consistency check failed: inconsistent " + kind
                                + " BlockIndex at height " + cursor.height());
            }
            BlockIndex parent = lookup.find(cursor.previousBlockHash());
            if (parent == null || parent.height() != cursor.height() - 1) {
                throw new IllegalStateException(
                        "Startup consistency check failed: missing/inconsistent " + kind
                                + " ancestor of " + cursor.hash().toDisplayHex());
            }
            BlockIndex expected = BlockIndexFactory.createChild(parent, cursor.header());
            if (!expected.chainWork().equals(cursor.chainWork())) {
                throw new IllegalStateException(
                        "Startup consistency check failed: inconsistent accumulated chain work at "
                                + cursor.hash().toDisplayHex());
            }
            cursor = parent;
            checked++;
        }
        if (!BlockIndexStorageMapper.toStored(cursor)
                .equals(BlockIndexStorageMapper.toStored(genesis))) {
            throw new IllegalStateException(
                    "Startup consistency check failed: " + kind
                            + " chain does not terminate at selected genesis");
        }
        return checked;
    }

    private long verifyRecentActiveData(
            BlockIndex activeTip,
            BlockIndexLookup lookup,
            RocksDbBlockStore blocks,
            RocksDbUndoStore undos
    ) {
        if (recentDataDepth == 0) return 0L;
        BlockIndex cursor = activeTip;
        long checked = 0L;
        while (cursor.height() > 0 && checked < recentDataDepth) {
            if (blocks.find(cursor.hash()).isEmpty()) {
                throw new IllegalStateException(
                        "Startup consistency check failed: recent active block body is missing at height "
                                + cursor.height() + ": " + cursor.hash().toDisplayHex());
            }
            if (undos.find(cursor.hash()).isEmpty()) {
                throw new IllegalStateException(
                        "Startup consistency check failed: recent active undo data is missing at height "
                                + cursor.height() + ": " + cursor.hash().toDisplayHex());
            }
            BlockIndex parent = lookup.find(cursor.previousBlockHash());
            if (parent == null) {
                throw new IllegalStateException(
                        "Startup consistency check failed: recent active parent is missing");
            }
            cursor = parent;
            checked++;
        }
        return checked;
    }

    public record Result(
            long activeIndexEntriesChecked,
            long headerIndexEntriesChecked,
            long recentBlocksAndUndoChecked
    ) {
    }
}
