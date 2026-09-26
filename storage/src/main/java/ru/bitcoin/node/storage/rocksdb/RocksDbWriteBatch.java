package ru.bitcoin.node.storage.rocksdb;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

public final class RocksDbWriteBatch
        implements AutoCloseable {

    private final WriteBatch batch;
    private final java.util.BitSet changedPrefixes = new java.util.BitSet(256);
    private final java.util.Map<ByteArrayKey, byte[]> pendingPuts = new java.util.HashMap<>();
    private final java.util.Set<ByteArrayKey> pendingDeletes = new java.util.HashSet<>();
    private final java.util.BitSet deletedPrefixes = new java.util.BitSet(256);

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
            ByteArrayKey tracked = new ByteArrayKey(key);
            pendingPuts.put(tracked, value.clone());
            pendingDeletes.remove(tracked);
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
            ByteArrayKey tracked = new ByteArrayKey(key);
            pendingPuts.remove(tracked);
            pendingDeletes.add(tracked);
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
            deletedPrefixes.set(unsignedPrefix);
            pendingPuts.keySet().removeIf(k -> k.prefix() == unsignedPrefix);
            pendingDeletes.removeIf(k -> k.prefix() == unsignedPrefix);
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to add namespace delete to RocksDB batch",
                    e
            );
        }
    }


    /** Read-your-writes lookup for stores maintaining metadata in the same atomic batch. */
    public PendingValue pendingValue(byte[] key) {
        ensureOpen();
        if (key == null) throw new IllegalArgumentException("key must not be null");
        ByteArrayKey tracked = new ByteArrayKey(key);
        byte[] value = pendingPuts.get(tracked);
        if (value != null) return new PendingValue(true, value);
        if (pendingDeletes.contains(tracked)
                || (key.length > 0 && deletedPrefixes.get(Byte.toUnsignedInt(key[0])))) {
            return new PendingValue(true, null);
        }
        return new PendingValue(false, null);
    }

    public record PendingValue(boolean touched, byte[] value) {
        public PendingValue {
            value = value == null ? null : value.clone();
        }
        @Override public byte[] value() { return value == null ? null : value.clone(); }
    }

    private static final class ByteArrayKey {
        private final byte[] bytes;
        private final int hash;
        private ByteArrayKey(byte[] bytes) {
            this.bytes = bytes.clone();
            this.hash = java.util.Arrays.hashCode(this.bytes);
        }
        private int prefix() {
            return bytes.length == 0 ? -1 : Byte.toUnsignedInt(bytes[0]);
        }
        @Override public boolean equals(Object other) {
            return other instanceof ByteArrayKey that && java.util.Arrays.equals(bytes, that.bytes);
        }
        @Override public int hashCode() { return hash; }
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
