package ru.bitcoin.node.chain.storage;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexStorageMapper;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Trusted persistence boundary for validated headers. All stores must belong to the
 * supplied database. The caller serializes validation and persistence with every
 * other chain writer; this class does not select or validate the best chain.
 */
public final class KnownHeaderStorage {

    private final RocksDbDatabase database;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;

    public KnownHeaderStorage(
            RocksDbDatabase database,
            RocksDbBlockIndexStore blockIndexStore,
            RocksDbChainStateStore chainStateStore
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.database = database;
        this.blockIndexStore = blockIndexStore;
        this.chainStateStore = chainStateStore;
    }

    /**
     * Atomically persists one validated HEADERS batch. All indexes in {@code newIndexes}
     * must have been proven absent by the validation lookup and ordered parents first.
     * A non-null bestHeaderTip must refer to an index in this batch or already stored.
     */
    public void saveBatch(
            List<BlockIndex> newIndexes,
            BlockIndex bestHeaderTip
    ) {
        saveBatch(newIndexes, bestHeaderTip, null);
    }

    /**
     * Persists a validated batch using skip ancestors already resolved by the validation
     * overlay. This removes the second ancestry walk (and its RocksDB reads) from header IBD.
     * A null map retains the compatibility path used by standalone callers.
     * Non-null maps must supply the validated, same-branch skip ancestor for every
     * non-genesis index; these trusted results are not revalidated against the database.
     */
    public void saveBatch(
            List<BlockIndex> newIndexes,
            BlockIndex bestHeaderTip,
            Map<Hash256, Hash256> resolvedSkipHashes
    ) {
        if (newIndexes == null) {
            throw new IllegalArgumentException("newIndexes must not be null");
        }
        if (newIndexes.stream().anyMatch(index -> index == null)) {
            throw new IllegalArgumentException("newIndexes must not contain null");
        }

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            Map<Hash256, BlockIndex> local =
                    resolvedSkipHashes == null ? new HashMap<>() : null;
            Map<Hash256, Hash256> localSkips =
                    resolvedSkipHashes == null ? new HashMap<>() : null;
            for (BlockIndex index : newIndexes) {
                blockIndexStore.saveNew(batch, BlockIndexStorageMapper.toStored(index));
                if (index.height() > 0) {
                    Hash256 skipHash;
                    if (resolvedSkipHashes != null) {
                        skipHash = resolvedSkipHashes.get(index.hash());
                        if (skipHash == null) {
                            throw new IllegalStateException(
                                    "Missing pre-resolved skip hash for " + index.hash().toDisplayHex());
                        }
                    } else {
                        skipHash = resolveSkipHash(index, local, localSkips);
                        localSkips.put(index.hash(), skipHash);
                    }
                    blockIndexStore.saveSkipNew(batch, BlockIndexStorageMapper.toStored(index), skipHash);
                }
                if (local != null) local.put(index.hash(), index);
            }
            if (bestHeaderTip != null) {
                chainStateStore.saveBestHeaderTipHash(
                        batch,
                        bestHeaderTip.hash()
                );
            }
            if (!newIndexes.isEmpty() || bestHeaderTip != null) {
                database.write(batch, true);
            }
        }
    }

    private Hash256 resolveSkipHash(BlockIndex index, Map<Hash256, BlockIndex> local,
                                    Map<Hash256, Hash256> localSkips) {
        long target = RocksDbBlockIndexStore.getSkipHeight(index.height());
        BlockIndex current = index;
        while (current.height() > target) {
            long skipHeight = RocksDbBlockIndexStore.getSkipHeight(current.height());
            long skipPrev = RocksDbBlockIndexStore.getSkipHeight(current.height() - 1);
            // Start with the parent: a new/replaced record has no trusted skip of its own.
            Hash256 skip = current == index ? null : localSkips.get(current.hash());
            if (skip == null && current != index) {
                skip = blockIndexStore.findSkipHash(current.hash()).orElse(null);
            }
            boolean useSkip = skip != null && (skipHeight == target
                    || (skipHeight > target && !(skipPrev < skipHeight - 2 && skipPrev >= target)));
            Hash256 nextHash = useSkip ? skip : current.previousBlockHash();
            BlockIndex next = local.get(nextHash);
            if (next == null) {
                next = blockIndexStore.find(nextHash).map(BlockIndexStorageMapper::fromStored).orElse(null);
            }
            if (next == null) throw new IllegalStateException("Missing ancestor while creating skip index");
            long expectedHeight = useSkip ? skipHeight : current.height() - 1;
            if (next.height() != expectedHeight) {
                throw new IllegalStateException("Invalid ancestor height while creating skip index: expected "
                        + expectedHeight + " but found " + next.height());
            }
            current = next;
        }
        return current.hash();
    }

    public void save(
            BlockIndex blockIndex,
            boolean updateBestHeaderTip
    ) {
        save(blockIndex, updateBestHeaderTip, true);
    }

    /**
     * Persists one validated header. Intermediate headers of a received HEADERS
     * message may be committed with {@code durable=false}; the final header is
     * committed synchronously, which flushes the WAL for the complete message.
     * Ancestors must already be stored. Prefer saveBatch for network HEADERS:
     * separate save calls do not provide whole-message atomicity.
     */
    public void save(
            BlockIndex blockIndex,
            boolean updateBestHeaderTip,
            boolean durable
    ) {
        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            blockIndexStore.save(
                    batch,
                    BlockIndexStorageMapper.toStored(
                            blockIndex
                    )
            );

            if (blockIndex.height() > 0) {
                blockIndexStore.saveSkipNew(batch, BlockIndexStorageMapper.toStored(blockIndex),
                        resolveSkipHash(blockIndex, Map.of(), Map.of()));
            }

            if (updateBestHeaderTip) {
                chainStateStore.saveBestHeaderTipHash(
                        batch,
                        blockIndex.hash()
                );
            }

            database.write(
                    batch,
                    durable
            );
        }
    }
}
