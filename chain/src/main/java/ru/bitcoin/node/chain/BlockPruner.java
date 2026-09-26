package ru.bitcoin.node.chain;

import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.Comparator;
import java.util.Objects;

/** Deletes old raw block and undo payloads while retaining block-index metadata and the reorg window. */
public final class BlockPruner {
    public static final int MIN_BLOCKS_TO_KEEP = 288;
    /** Sentinel used by the application for Core-style manual-only prune mode (-prune=1). */
    public static final long MANUAL_ONLY = -1L;

    private final RocksDbDatabase database;
    private final long targetBytes;
    private final int blocksToKeep;

    public BlockPruner(RocksDbDatabase database, long targetBytes) {
        this(database, targetBytes, MIN_BLOCKS_TO_KEEP);
    }

    BlockPruner(RocksDbDatabase database, long targetBytes, int blocksToKeep) {
        this.database = Objects.requireNonNull(database, "database");
        if (targetBytes < 0 && targetBytes != MANUAL_ONLY)
            throw new IllegalArgumentException("targetBytes must be non-negative or MANUAL_ONLY");
        if (blocksToKeep < 1) throw new IllegalArgumentException("blocksToKeep must be positive");
        this.targetBytes = targetBytes;
        this.blocksToKeep = blocksToKeep;
    }

    public boolean enabled() { return targetBytes != 0; }
    public boolean automatic() { return targetBytes > 0; }
    public boolean manualOnly() { return targetBytes == MANUAL_ONLY; }
    public long targetBytes() { return automatic() ? targetBytes : 0L; }

    /** Automatic target-based pruning. Manual-only mode never prunes from this path. */
    public Result prune(BlockIndex activeTip) {
        Objects.requireNonNull(activeTip, "activeTip");
        synchronized (database) {
            long before = usageBytes();
            if (!automatic() || before <= targetBytes) return new Result(before, before, 0L, -1L);
            long maxPruneHeight = activeTip.height() - blocksToKeep;
            if (maxPruneHeight <= 0) return new Result(before, before, 0L, -1L);
            return pruneCandidates(maxPruneHeight, targetBytes, before);
        }
    }

    /**
     * Manual pruning up to a requested height. The protected reorg window always wins,
     * so a request close to the tip is clamped to tip - MIN_BLOCKS_TO_KEEP.
     */
    public Result pruneToHeight(BlockIndex activeTip, long requestedHeight) {
        Objects.requireNonNull(activeTip, "activeTip");
        if (!enabled()) throw new IllegalStateException("Node is not in prune mode");
        if (requestedHeight < 0) throw new IllegalArgumentException("Prune height must not be negative");
        synchronized (database) {
            long before = usageBytes();
            long maxSafeHeight = activeTip.height() - blocksToKeep;
            if (maxSafeHeight <= 0)
                throw new IllegalStateException("Blockchain is too short for pruning");
            long cutoff = Math.min(requestedHeight, maxSafeHeight);
            return pruneCandidates(cutoff, 0L, before);
        }
    }

    private Result pruneCandidates(long maxHeight, long stopAtBytes, long before) {
        var indexes = new RocksDbBlockIndexStore(database);
        var blocks = new RocksDbBlockStore(database);
        var undos = new RocksDbUndoStore(database);
        var state = new RocksDbPruneStateStore(database);
        var availability = new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database);

        // Use the block index as metadata. Do not deserialize every raw block merely to
        // discover its hash/height; this remains bounded by index records even for large blocks.
        var candidates = indexes.findAll().stream()
                .filter(index -> index.height() > 0 && index.height() <= maxHeight)
                .filter(index -> availability.hasData(index.hash()))
                .sorted(Comparator.comparingLong(ru.bitcoin.node.storage.block.StoredBlockIndex::height))
                .toList();

        long usage = before;
        long pruned = 0L;
        long highest = -1L;
        for (var candidate : candidates) {
            if (stopAtBytes > 0 && usage <= stopAtBytes) break;
            long blockBytes = blocks.serializedSize(candidate.hash());
            long undoBytes = undos.serializedSize(candidate.hash());
            try (var batch = new RocksDbWriteBatch()) {
                blocks.delete(batch, candidate.hash());
                undos.delete(batch, candidate.hash());
                availability.clearDataAndUndo(batch, candidate.hash());
                state.recordHighestPrunedHeight(batch, candidate.height());
                database.write(batch);
            }
            usage = Math.max(0L, usage - blockBytes - undoBytes);
            pruned++;
            highest = Math.max(highest, candidate.height());
        }
        return new Result(before, usageBytes(), pruned, highest);
    }

    private long usageBytes() {
        return new RocksDbPruneUsageStore(database).usageBytes();
    }

    public record Result(long bytesBefore, long bytesAfter, long blocksPruned, long highestPrunedHeight) {}
}
