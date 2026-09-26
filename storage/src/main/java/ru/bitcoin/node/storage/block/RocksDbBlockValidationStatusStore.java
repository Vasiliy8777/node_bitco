package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;

/** Durable contextual-validation level for block-index entries. */
public final class RocksDbBlockValidationStatusStore {
    private static final byte PREFIX = 0x11;
    private static final int HASH_SIZE = 32;
    public static final int SCRIPTS_VALID = 1;

    private final RocksDbDatabase database;

    public RocksDbBlockValidationStatusStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public boolean isScriptsValid(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        byte[] value = database.get(key(hash));
        if (value == null) return false;
        if (value.length != 1 || Byte.toUnsignedInt(value[0]) != SCRIPTS_VALID)
            throw new IllegalStateException("Invalid block validation status metadata");
        return true;
    }

    public void markScriptsValid(RocksDbWriteBatch batch, Hash256 hash) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(hash, "hash");
        batch.put(key(hash), new byte[]{(byte) SCRIPTS_VALID});
    }

    public void clear(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.deletePrefix(PREFIX);
    }

    private static byte[] key(Hash256 hash) {
        byte[] raw = hash.bytes();
        if (raw.length != HASH_SIZE) throw new IllegalStateException("Invalid block hash length");
        byte[] key = new byte[1 + HASH_SIZE];
        key[0] = PREFIX;
        System.arraycopy(raw, 0, key, 1, HASH_SIZE);
        return key;
    }
}
