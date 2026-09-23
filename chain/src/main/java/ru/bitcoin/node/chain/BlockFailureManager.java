package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Comparator;
import java.util.Objects;

public final class BlockFailureManager {

    private final RocksDbDatabase database;
    private final RocksDbBlockFailureStore failureStore;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;
    private final BlockFailureResolver failureResolver;

    public BlockFailureManager(
            RocksDbDatabase database,
            RocksDbBlockFailureStore failureStore,
            RocksDbBlockIndexStore blockIndexStore,
            RocksDbChainStateStore chainStateStore,
            BlockFailureResolver failureResolver
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.failureStore = Objects.requireNonNull(failureStore, "failureStore");
        this.blockIndexStore = Objects.requireNonNull(blockIndexStore, "blockIndexStore");
        this.chainStateStore = Objects.requireNonNull(chainStateStore, "chainStateStore");
        this.failureResolver = Objects.requireNonNull(failureResolver, "failureResolver");
    }

    public synchronized void markFailed(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");

        BlockIndex failed = blockIndexStore.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot mark missing BlockIndex as failed: " + hash.toDisplayHex()));

        if (failed.height() == 0) {
            throw new IllegalStateException("Genesis block cannot be marked failed");
        }

        BlockIndex bestEligible = blockIndexStore.findAll().stream()
                .map(BlockIndexStorageMapper::fromStored)
                .filter(index -> !failureResolver.isFailed(index, hash))
                .max(Comparator
                        .comparing(BlockIndex::chainWork)
                        .thenComparingLong(BlockIndex::height)
                        .thenComparing(index -> index.hash().toDisplayHex()))
                .orElseThrow(() -> new IllegalStateException(
                        "No eligible block index remains after invalidating " + hash.toDisplayHex()));

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            failureStore.markFailed(batch, hash);
            chainStateStore.saveBestHeaderTipHash(batch, bestEligible.hash());
            database.write(batch);
        }
    }
}
