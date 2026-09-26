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


    /** Computes the strongest candidate as if {@code hash} were failed, without writing anything. */
    public synchronized BlockIndex bestEligibleAfterInvalidating(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        BlockIndex failed = blockIndexStore.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot invalidate missing BlockIndex: " + hash.toDisplayHex()));
        if (failed.height() == 0) throw new IllegalStateException("Genesis block cannot be marked failed");
        return blockIndexStore.findBest(stored ->
                        !failureResolver.isFailed(BlockIndexStorageMapper.fromStored(stored), hash))
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "No eligible block index remains after invalidating " + hash.toDisplayHex()));
    }

    /** Appends manual invalidation metadata to a caller-owned atomic RocksDB batch. */
    public synchronized void appendInvalidation(
            RocksDbWriteBatch batch, Hash256 hash, BlockIndex bestEligible) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(bestEligible, "bestEligible");
        failureStore.markFailed(batch, hash);
        chainStateStore.saveBestHeaderTipHash(batch, bestEligible.hash());
    }

    /**
     * Precomputes reconsiderblock semantics without mutating persistent failure state.
     * The strongest eligible header is kept separate from the strongest candidate whose
     * block body is locally available for an immediate chain transition.
     */
    public synchronized ReconsiderationPlan prepareReconsideration(
            Hash256 hash,
            java.util.function.Predicate<BlockIndex> bodyAvailable
    ) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(bodyAvailable, "bodyAvailable");

        BlockIndex target = blockIndexStore.find(hash)
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot reconsider missing BlockIndex: " + hash.toDisplayHex()));

        java.util.Set<Hash256> rootsToClear = new java.util.HashSet<>();
        for (var stored : blockIndexStore.findAll()) {
            BlockIndex candidate = BlockIndexStorageMapper.fromStored(stored);
            if (!failureStore.isFailed(candidate.hash())) continue;
            if (isAncestor(candidate, target) || isAncestor(target, candidate)) {
                rootsToClear.add(candidate.hash());
            }
        }

        BlockIndex bestHeader = blockIndexStore.findBest(stored ->
                        !isFailedAfterClearing(BlockIndexStorageMapper.fromStored(stored), rootsToClear))
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "No eligible block index remains after reconsidering " + hash.toDisplayHex()));

        BlockIndex bestAvailable = blockIndexStore.findBest(stored -> {
                    BlockIndex candidate = BlockIndexStorageMapper.fromStored(stored);
                    return !isFailedAfterClearing(candidate, rootsToClear) && bodyAvailable.test(candidate);
                })
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException(
                        "No eligible block with available data remains after reconsidering "
                                + hash.toDisplayHex()));

        return new ReconsiderationPlan(
                java.util.Set.copyOf(rootsToClear),
                bestHeader,
                bestAvailable
        );
    }

    /** Commits reconsideration metadata when no active-chain transition is required. */
    public synchronized void commitReconsideration(ReconsiderationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            appendReconsideration(batch, plan);
            database.write(batch);
        }
    }

    /** Appends a previously prepared reconsideration to a caller-owned atomic batch. */
    public synchronized void appendReconsideration(
            RocksDbWriteBatch batch,
            ReconsiderationPlan plan
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(plan, "plan");
        for (Hash256 root : plan.failureRootsToClear()) {
            failureStore.clearFailed(batch, root);
        }
        chainStateStore.saveBestHeaderTipHash(batch, plan.bestHeader().hash());
    }

    private boolean isFailedAfterClearing(BlockIndex index, java.util.Set<Hash256> rootsToClear) {
        BlockIndex cursor = index;
        java.util.Set<Hash256> visited = new java.util.HashSet<>();
        while (true) {
            if (!visited.add(cursor.hash())) {
                throw new IllegalStateException(
                        "Cycle detected in block-index ancestry at " + cursor.hash().toDisplayHex());
            }
            if (!rootsToClear.contains(cursor.hash()) && failureStore.isFailed(cursor.hash())) {
                return true;
            }
            if (cursor.height() == 0) return false;
            Hash256 previous = cursor.previousBlockHash();
            cursor = blockIndexStore.find(previous)
                    .map(BlockIndexStorageMapper::fromStored)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing BlockIndex ancestor: " + previous.toDisplayHex()));
        }
    }

    public record ReconsiderationPlan(
            java.util.Set<Hash256> failureRootsToClear,
            BlockIndex bestHeader,
            BlockIndex bestAvailable
    ) {
        public ReconsiderationPlan {
            failureRootsToClear = java.util.Set.copyOf(
                    Objects.requireNonNull(failureRootsToClear, "failureRootsToClear"));
            Objects.requireNonNull(bestHeader, "bestHeader");
            Objects.requireNonNull(bestAvailable, "bestAvailable");
        }
    }

    /**
     * Metadata-only compatibility entry point. Application chain control should use
     * prepareReconsideration plus an atomic chain-transition commit.
     */
    public synchronized void reconsider(Hash256 hash) {
        ReconsiderationPlan plan = prepareReconsideration(hash, ignored -> true);
        commitReconsideration(plan);
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
