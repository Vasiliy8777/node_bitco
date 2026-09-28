package ru.bitcoin.node.chain;

import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.Objects;

/** Deletes old raw block and undo payloads while retaining block-index metadata and the reorg window. */
public final class BlockPruner {
    public static final int MIN_BLOCKS_TO_KEEP = 288;
    /** Sentinel used by the application for Core-style manual-only prune mode (-prune=1). */
    public static final long MANUAL_ONLY = -1L;

    private final RocksDbDatabase database;
    private final long targetBytes;
    private final int blocksToKeep;
    private final long pruneAfterHeight;

    public BlockPruner(RocksDbDatabase database, long targetBytes) {
        this(database, targetBytes, MIN_BLOCKS_TO_KEEP, 0L);
    }

    public BlockPruner(RocksDbDatabase database, long targetBytes, long pruneAfterHeight) {
        this(database, targetBytes, MIN_BLOCKS_TO_KEEP, pruneAfterHeight);
    }

    BlockPruner(RocksDbDatabase database, long targetBytes, int blocksToKeep) {
        this(database, targetBytes, blocksToKeep, 0L);
    }

    BlockPruner(RocksDbDatabase database, long targetBytes, int blocksToKeep, long pruneAfterHeight) {
        this.database = Objects.requireNonNull(database, "database");
        if (targetBytes < 0 && targetBytes != MANUAL_ONLY)
            throw new IllegalArgumentException("targetBytes must be non-negative or MANUAL_ONLY");
        if (blocksToKeep < 1) throw new IllegalArgumentException("blocksToKeep must be positive");
        if (pruneAfterHeight < 0) throw new IllegalArgumentException("pruneAfterHeight must not be negative");
        this.targetBytes = targetBytes;
        this.blocksToKeep = blocksToKeep;
        this.pruneAfterHeight = pruneAfterHeight;
    }

    public boolean enabled() { return targetBytes != 0; }
    public boolean automatic() { return targetBytes > 0; }
    public boolean manualOnly() { return targetBytes == MANUAL_ONLY; }
    public long targetBytes() { return automatic() ? targetBytes : 0L; }

    /** Automatic target-based pruning. Manual-only mode never prunes from this path. */
    public Result prune(BlockIndex activeTip) {
        return prune(activeTip, Long.MAX_VALUE);
    }

    /**
     * Automatic pruning with an additional inclusive ceiling. The ceiling is used by
     * AssumeUTXO background validation so unvalidated historical block bodies are never removed.
     */
    public Result prune(BlockIndex activeTip, long pruneCeilingHeight) {
        Objects.requireNonNull(activeTip, "activeTip");
        if (pruneCeilingHeight < 0) throw new IllegalArgumentException("pruneCeilingHeight must not be negative");
        synchronized (database) {
            long before = usageBytes();
            if (!automatic() || before <= targetBytes || activeTip.height() < pruneAfterHeight)
                return new Result(before, before, 0L, -1L);
            long maxPruneHeight = Math.min(activeTip.height() - blocksToKeep, pruneCeilingHeight);
            if (maxPruneHeight <= 0) return new Result(before, before, 0L, -1L);
            return pruneCandidates(maxPruneHeight, targetBytes, before);
        }
    }

    /**
     * Manual pruning up to a requested height. The protected reorg window always wins,
     * so a request close to the tip is clamped to tip - MIN_BLOCKS_TO_KEEP.
     */
    public Result pruneToHeight(BlockIndex activeTip, long requestedHeight) {
        return pruneToHeight(activeTip, requestedHeight, Long.MAX_VALUE);
    }

    /** Manual pruning with the same additional safety ceiling used by automatic pruning. */
    public Result pruneToHeight(BlockIndex activeTip, long requestedHeight, long pruneCeilingHeight) {
        Objects.requireNonNull(activeTip, "activeTip");
        if (!enabled()) throw new IllegalStateException("Node is not in prune mode");
        if (requestedHeight < 0) throw new IllegalArgumentException("Prune height must not be negative");
        if (pruneCeilingHeight < 0) throw new IllegalArgumentException("pruneCeilingHeight must not be negative");
        synchronized (database) {
            long before = usageBytes();
            if (activeTip.height() < pruneAfterHeight)
                throw new IllegalStateException("Blockchain is too short for pruning");
            long ordinaryMaxSafeHeight = activeTip.height() - blocksToKeep;
            if (ordinaryMaxSafeHeight <= 0)
                throw new IllegalStateException("Blockchain is too short for pruning");
            long maxSafeHeight = Math.min(ordinaryMaxSafeHeight, pruneCeilingHeight);
            if (maxSafeHeight <= 0)
                return new Result(before, before, 0L, -1L);
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

        // Height-index iteration is ordered and stoppable: no full BlockIndex list is retained.
        long[] usage = {before};
        long[] pruned = {0L};
        long[] highest = {-1L};

        indexes.visitByHeightAscending(candidate -> {
            if (candidate.height() > maxHeight) return false;
            if (candidate.height() == 0 || !availability.hasData(candidate.hash())) return true;
            if (stopAtBytes > 0 && usage[0] <= stopAtBytes) return false;

            long blockBytes = blocks.serializedSize(candidate.hash());
            long undoBytes = undos.serializedSize(candidate.hash());
            try (var batch = new RocksDbWriteBatch()) {
                blocks.delete(batch, candidate.hash());
                undos.delete(batch, candidate.hash());
                availability.clearDataAndUndo(batch, candidate.hash());
                state.recordHighestPrunedHeight(batch, candidate.height());
                database.write(batch);
            }
            usage[0] = Math.max(0L, usage[0] - blockBytes - undoBytes);
            pruned[0]++;
            highest[0] = Math.max(highest[0], candidate.height());
            return true;
        });
        return new Result(before, usageBytes(), pruned[0], highest[0]);
    }

    private long usageBytes() {
        return new RocksDbPruneUsageStore(database).usageBytes();
    }

    public record Result(long bytesBefore, long bytesAfter, long blocksPruned, long highestPrunedHeight) {}
}
