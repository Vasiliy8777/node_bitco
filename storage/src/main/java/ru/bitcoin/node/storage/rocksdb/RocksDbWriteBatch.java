package ru.bitcoin.node.storage.rocksdb;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

public final class RocksDbWriteBatch
        implements AutoCloseable {

    private final WriteBatch batch;

    private boolean closed;

    public RocksDbWriteBatch() {
        this.batch = new WriteBatch();
    }

    public void put(
            byte[] key,
            byte[] value
    ) {
        ensureOpen();

        if (key == null) {
            throw new IllegalArgumentException(
                    "key must not be null"
            );
        }

        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        try {
            batch.put(
                    key,
                    value
            );
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to add put operation to RocksDB batch",
                    e
            );
        }
    }

    public void delete(
            byte[] key
    ) {
        ensureOpen();

        if (key == null) {
            throw new IllegalArgumentException(
                    "key must not be null"
            );
        }

        try {
            batch.delete(
                    key
            );
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to add delete operation to RocksDB batch",
                    e
            );
        }
    }

    WriteBatch nativeBatch() {
        ensureOpen();
        return batch;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "RocksDbWriteBatch is already closed"
            );
        }
    }

    @Override
    public void close() {

        if (!closed) {
            closed = true;
            batch.close();
        }
    }
}