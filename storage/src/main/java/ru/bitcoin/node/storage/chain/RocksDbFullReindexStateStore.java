package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;

/** Durable marker used to resume a full block-index rebuild after interruption. */
public final class RocksDbFullReindexStateStore {
    private static final byte[] IN_PROGRESS_KEY = {0x0C, 0x01};
    private static final byte[] VALUE = {0x01};
    private final RocksDbDatabase database;

    public RocksDbFullReindexStateStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public boolean isInProgress() {
        byte[] value = database.get(IN_PROGRESS_KEY);
        if (value == null) return false;
        if (!java.util.Arrays.equals(value, VALUE)) {
            throw new IllegalStateException("Unsupported full-reindex marker value");
        }
        return true;
    }

    public void markInProgress(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.put(IN_PROGRESS_KEY, VALUE);
    }

    public void clear(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.delete(IN_PROGRESS_KEY);
    }
}
