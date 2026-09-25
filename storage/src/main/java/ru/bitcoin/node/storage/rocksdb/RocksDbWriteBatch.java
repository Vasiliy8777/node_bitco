package ru.bitcoin.node.storage.rocksdb;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

public final class RocksDbWriteBatch
        implements AutoCloseable {

    private final WriteBatch batch;
    private final java.util.BitSet changedPrefixes = new java.util.BitSet(256);

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
            if (key.length > 0) changedPrefixes.set(Byte.toUnsignedInt(key[0]));
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
            if (key.length > 0) changedPrefixes.set(Byte.toUnsignedInt(key[0]));
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to add delete operation to RocksDB batch",
                    e
            );
        }
    }

    /**
     * Deletes one complete one-byte key namespace as part of this batch.
     *
     * The operation is represented by a RocksDB range tombstone and therefore
     * remains atomic with the other operations in the same WriteBatch.
     */
    public void deletePrefix(byte prefix) {
        ensureOpen();
        int unsignedPrefix = Byte.toUnsignedInt(prefix);
        if (unsignedPrefix == 255) {
            throw new IllegalArgumentException(
                    "0xFF namespace cannot be range-deleted safely"
            );
        }
        try {
            batch.deleteRange(
                    new byte[]{prefix},
                    new byte[]{(byte) (unsignedPrefix + 1)}
            );
            changedPrefixes.set(unsignedPrefix);
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to add namespace delete to RocksDB batch",
                    e
            );
        }
    }

    WriteBatch nativeBatch() {
        ensureOpen();
        return batch;
    }

    java.util.BitSet changedPrefixes() {
        ensureOpen();
        return (java.util.BitSet) changedPrefixes.clone();
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
