package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Durable evidence that raw block/undo history has been pruned.
 */
public final class RocksDbPruneStateStore {
    private static final byte[] HIGHEST_PRUNED_HEIGHT_KEY = {0x0B, 0x01};
    private final RocksDbDatabase database;

    public RocksDbPruneStateStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public OptionalLong highestPrunedHeight() {
        byte[] value = database.get(HIGHEST_PRUNED_HEIGHT_KEY);
        if (value == null) return OptionalLong.empty();
        if (value.length != Long.BYTES)
            throw new IllegalStateException("Invalid prune-state height size: " + value.length);
        long height = ByteBuffer.wrap(value).getLong();
        if (height < 0) throw new IllegalStateException("Invalid negative pruned height: " + height);
        return OptionalLong.of(height);
    }

    public boolean hasPruned() {
        return highestPrunedHeight().isPresent();
    }

    public void recordHighestPrunedHeight(RocksDbWriteBatch batch, long height) {
        Objects.requireNonNull(batch, "batch");
        if (height < 0) throw new IllegalArgumentException("height must not be negative");
        long persisted = highestPrunedHeight().orElse(-1L);
        long next = Math.max(persisted, height);
        batch.put(HIGHEST_PRUNED_HEIGHT_KEY, ByteBuffer.allocate(Long.BYTES).putLong(next).array());
    }
}
