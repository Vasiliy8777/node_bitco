package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/** Deletes old raw block and undo payloads while retaining block-index metadata and the reorg window. */
public final class BlockPruner {
    public static final int MIN_BLOCKS_TO_KEEP = 288;
    private static final byte BLOCK_PREFIX = 0x05;
    private static final byte UNDO_PREFIX = 0x04;

    private final RocksDbDatabase database;
    private final long targetBytes;
    private final int blocksToKeep;

    public BlockPruner(RocksDbDatabase database, long targetBytes) {
        this(database, targetBytes, MIN_BLOCKS_TO_KEEP);
    }

    BlockPruner(RocksDbDatabase database, long targetBytes, int blocksToKeep) {
        this.database = Objects.requireNonNull(database, "database");
        if (targetBytes < 0) throw new IllegalArgumentException("targetBytes must not be negative");
        if (blocksToKeep < 1) throw new IllegalArgumentException("blocksToKeep must be positive");
        this.targetBytes = targetBytes;
        this.blocksToKeep = blocksToKeep;
    }

    public boolean enabled() { return targetBytes > 0; }

    public Result prune(BlockIndex activeTip) {
        Objects.requireNonNull(activeTip, "activeTip");
        synchronized (database) {
            long before = usageBytes();
            if (!enabled() || before <= targetBytes) return new Result(before, before, 0L, -1L);
            long maxPruneHeight = activeTip.height() - blocksToKeep;
            if (maxPruneHeight <= 0) return new Result(before, before, 0L, -1L);

            var indexes = new RocksDbBlockIndexStore(database);
            var blocks = new RocksDbBlockStore(database);
            var undos = new RocksDbUndoStore(database);
            var state = new RocksDbPruneStateStore(database);
            var candidates = new ArrayList<Candidate>();
            database.forEachValueByPrefix(BLOCK_PREFIX, value -> {
                var block = BlockParser.parse(value);
                var stored = indexes.find(block.hash()).orElse(null);
                if (stored != null && stored.height() > 0 && stored.height() <= maxPruneHeight) {
                    candidates.add(new Candidate(stored.height(), block.hash()));
                }
            });
            candidates.sort(Comparator.comparingLong(Candidate::height));

            long usage = before;
            long pruned = 0L;
            long highest = -1L;
            for (Candidate candidate : candidates) {
                if (usage <= targetBytes) break;
                long blockBytes = blocks.serializedSize(candidate.hash());
                long undoBytes = undos.serializedSize(candidate.hash());
                try (var batch = new RocksDbWriteBatch()) {
                    blocks.delete(batch, candidate.hash());
                    undos.delete(batch, candidate.hash());
                    state.recordHighestPrunedHeight(batch, candidate.height());
                    database.write(batch);
                }
                usage = Math.max(0L, usage - blockBytes - undoBytes);
                pruned++;
                highest = Math.max(highest, candidate.height());
            }
            return new Result(before, usageBytes(), pruned, highest);
        }
    }

    private long usageBytes() {
        return Math.addExact(database.valueBytesByPrefix(BLOCK_PREFIX), database.valueBytesByPrefix(UNDO_PREFIX));
    }

    private record Candidate(long height, ru.bitcoin.node.common.types.Hash256 hash) {}
    public record Result(long bytesBefore, long bytesAfter, long blocksPruned, long highestPrunedHeight) {}
}
