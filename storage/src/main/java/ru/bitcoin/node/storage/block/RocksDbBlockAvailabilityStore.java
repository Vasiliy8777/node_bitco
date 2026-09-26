package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;

/**
 * Durable block-index availability metadata, analogous to Core's HAVE_DATA/HAVE_UNDO status bits.
 *
 * <p>The namespace is deliberately separate from StoredBlockIndex v1 so existing databases can be
 * upgraded without rewriting the primary block index. Missing metadata is migrated lazily from the
 * legacy raw block/undo namespaces and then becomes authoritative.</p>
 */
public final class RocksDbBlockAvailabilityStore {
    private static final byte AVAILABILITY_PREFIX = 0x10;
    private static final byte BLOCK_PREFIX = 0x05;
    private static final byte UNDO_PREFIX = 0x04;
    private static final int HASH_SIZE = 32;

    public static final int HAVE_DATA = 1;
    public static final int HAVE_UNDO = 1 << 1;

    private final RocksDbDatabase database;

    public RocksDbBlockAvailabilityStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public int status(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        byte[] value = database.get(key(AVAILABILITY_PREFIX, hash));
        if (value != null) return decode(value);

        // Backward-compatible, restart-safe lazy migration from pre-metadata databases.
        synchronized (database) {
            value = database.get(key(AVAILABILITY_PREFIX, hash));
            if (value != null) return decode(value);
            int migrated = 0;
            if (database.get(key(BLOCK_PREFIX, hash)) != null) migrated |= HAVE_DATA;
            if (database.get(key(UNDO_PREFIX, hash)) != null) migrated |= HAVE_UNDO;
            database.put(key(AVAILABILITY_PREFIX, hash), new byte[]{(byte) migrated});
            return migrated;
        }
    }

    public boolean hasData(Hash256 hash) { return (status(hash) & HAVE_DATA) != 0; }
    public boolean hasUndo(Hash256 hash) { return (status(hash) & HAVE_UNDO) != 0; }

    public void markData(RocksDbWriteBatch batch, Hash256 hash) {
        update(batch, hash, HAVE_DATA, 0);
    }

    public void markUndo(RocksDbWriteBatch batch, Hash256 hash) {
        update(batch, hash, HAVE_UNDO, 0);
    }

    public void clearData(RocksDbWriteBatch batch, Hash256 hash) {
        update(batch, hash, 0, HAVE_DATA);
    }

    public void clearUndo(RocksDbWriteBatch batch, Hash256 hash) {
        update(batch, hash, 0, HAVE_UNDO);
    }

    public void clearDataAndUndo(RocksDbWriteBatch batch, Hash256 hash) {
        update(batch, hash, 0, HAVE_DATA | HAVE_UNDO);
    }

    public void clear(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.deletePrefix(AVAILABILITY_PREFIX);
    }

    private void update(RocksDbWriteBatch batch, Hash256 hash, int set, int clear) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(hash, "hash");
        int next = (status(hash) | set) & ~clear;
        batch.put(key(AVAILABILITY_PREFIX, hash), new byte[]{(byte) next});
    }

    private static int decode(byte[] value) {
        if (value.length != 1) throw new IllegalStateException("Invalid block availability metadata size");
        int status = Byte.toUnsignedInt(value[0]);
        if ((status & ~(HAVE_DATA | HAVE_UNDO)) != 0)
            throw new IllegalStateException("Unsupported block availability flags: " + status);
        return status;
    }

    private static byte[] key(byte prefix, Hash256 hash) {
        byte[] raw = hash.bytes();
        if (raw.length != HASH_SIZE) throw new IllegalStateException("Invalid block hash length: " + raw.length);
        byte[] key = new byte[1 + HASH_SIZE];
        key[0] = prefix;
        System.arraycopy(raw, 0, key, 1, HASH_SIZE);
        return key;
    }
}
