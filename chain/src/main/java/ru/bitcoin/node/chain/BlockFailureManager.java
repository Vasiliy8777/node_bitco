package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

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

        BlockIndex bestEligible = blockIndexStore
                .findBest(stored -> !failureResolver.isFailed(BlockIndexStorageMapper.fromStored(stored), hash))
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "No eligible block index remains after invalidating " + hash.toDisplayHex()));

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            failureStore.markFailed(batch, hash);
            chainStateStore.saveBestHeaderTipHash(batch, bestEligible.hash());
            database.write(batch);
        }
    }

    /**
     * Clears manual/recorded failure roots related to this block, matching reconsider semantics.
     */
    public synchronized void reconsider(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        BlockIndex target = blockIndexStore.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot reconsider missing BlockIndex: " + hash.toDisplayHex()));

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            for (var stored : blockIndexStore.findAll()) {
                BlockIndex candidate = BlockIndexStorageMapper.fromStored(stored);
                if (!failureStore.isFailed(candidate.hash())) continue;
                if (isAncestor(candidate, target) || isAncestor(target, candidate)) {
                    failureStore.clearFailed(batch, candidate.hash());
                }
            }
            database.write(batch);
        }

        BlockIndex bestEligible = bestEligible();
        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            chainStateStore.saveBestHeaderTipHash(batch, bestEligible.hash());
            database.write(batch);
        }
    }

    public synchronized BlockIndex bestEligible() {
        return blockIndexStore.findBest(stored ->
                        !failureResolver.isFailed(BlockIndexStorageMapper.fromStored(stored)))
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException("No eligible block index remains"));
    }

    public synchronized boolean isFailed(Hash256 hash) {
        BlockIndex index = blockIndexStore.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing BlockIndex: " + hash.toDisplayHex()));
        return failureResolver.isFailed(index);
    }

    private boolean isAncestor(BlockIndex ancestor, BlockIndex descendant) {
        if (ancestor.height() > descendant.height()) return false;
        BlockIndex cursor = descendant;
        while (cursor.height() > ancestor.height()) {
            Hash256 previous = cursor.previousBlockHash();
            cursor = blockIndexStore.find(previous)
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing BlockIndex ancestor: " + previous.toDisplayHex()));
        }
        return cursor.hash().equals(ancestor.hash());
    }

}
