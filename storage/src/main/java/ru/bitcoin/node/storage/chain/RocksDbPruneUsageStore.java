package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Compact persistent serialized-payload byte index used by pruning. */
public final class RocksDbPruneUsageStore {
    private static final byte BLOCK_SIZE_PREFIX = RocksDbNamespaces.PRUNE_BLOCK_SIZE;
    private static final byte UNDO_SIZE_PREFIX = RocksDbNamespaces.PRUNE_UNDO_SIZE;
    private static final byte[] VERSION_KEY = RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION);
    private static final byte[] LEGACY_VERSION_KEY = RocksDbNamespaces.LEGACY_PRUNE_USAGE_VERSION_KEY;
    private static final byte VERSION = 1;
    private static final byte BLOCK_PREFIX = 0x05;
    private static final byte UNDO_PREFIX = 0x04;
    private static final int HASH_SIZE = 32;
    private final RocksDbDatabase database;

    public RocksDbPruneUsageStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** One-time restart-safe migration for databases created before the size index existed. */
    public void ensureMigrated() {
        synchronized (database) {
            byte[] version = database.get(VERSION_KEY);
            if (version != null) {
                requireSupportedVersion(version);
                return;
            }

            byte[] legacyVersion = database.get(LEGACY_VERSION_KEY);
            if (legacyVersion != null) {
                requireSupportedVersion(legacyVersion);
                try (var batch = new RocksDbWriteBatch()) {
                    batch.put(VERSION_KEY, new byte[]{VERSION});
                    batch.delete(LEGACY_VERSION_KEY);
                    database.write(batch);
                }
                return;
            }

            rebuildPrefix(BLOCK_PREFIX, BLOCK_SIZE_PREFIX);
            rebuildPrefix(UNDO_PREFIX, UNDO_SIZE_PREFIX);
            database.put(VERSION_KEY, new byte[]{VERSION});
        }
    }

    private static void requireSupportedVersion(byte[] version) {
        if (version.length != 1 || version[0] != VERSION)
            throw new IllegalStateException("Unsupported prune usage index version");
    }

    private void rebuildPrefix(byte payloadPrefix, byte sizePrefix) {
        try (var clear = new RocksDbWriteBatch()) {
            clear.deletePrefix(sizePrefix);
            database.write(clear);
        }
        final RocksDbWriteBatch[] batch = {new RocksDbWriteBatch()};
        final int[] count = {0};
        try {
            database.forEachEntryByPrefix(payloadPrefix, (key, value) -> {
                if (key.length != 1 + HASH_SIZE)
                    throw new IllegalStateException("Invalid payload key length in prune usage migration");
                byte[] sizeKey = key.clone();
                sizeKey[0] = sizePrefix;
                batch[0].put(sizeKey, encodeSize(value.length));
                if (++count[0] == 4096) {
                    database.write(batch[0]);
                    batch[0].close();
                    batch[0] = new RocksDbWriteBatch();
                    count[0] = 0;
                }
            });
            if (count[0] != 0) database.write(batch[0]);
        } finally {
            batch[0].close();
        }
    }

    public void setBlockSize(RocksDbWriteBatch batch, Hash256 hash, long size) {
        setSize(batch, BLOCK_SIZE_PREFIX, hash, size);
    }
    public void setUndoSize(RocksDbWriteBatch batch, Hash256 hash, long size) {
        setSize(batch, UNDO_SIZE_PREFIX, hash, size);
    }
    public long blockSize(Hash256 hash) { ensureMigrated(); return size(BLOCK_SIZE_PREFIX, hash); }
    public long undoSize(Hash256 hash) { ensureMigrated(); return size(UNDO_SIZE_PREFIX, hash); }
    public long usageBytes() {
        ensureMigrated();
        return Math.addExact(sum(BLOCK_SIZE_PREFIX), sum(UNDO_SIZE_PREFIX));
    }
    public void clearUndoSizes(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.deletePrefix(UNDO_SIZE_PREFIX);
    }

    private void setSize(RocksDbWriteBatch batch, byte prefix, Hash256 hash, long size) {
        Objects.requireNonNull(batch, "batch"); Objects.requireNonNull(hash, "hash");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        ensureMigrated();
        byte[] key = key(prefix, hash);
        if (size == 0) batch.delete(key); else batch.put(key, encodeSize(size));
    }
    private long size(byte prefix, Hash256 hash) {
        byte[] value = database.get(key(prefix, Objects.requireNonNull(hash, "hash")));
        return value == null ? 0L : decodeSize(value);
    }
    private long sum(byte prefix) {
        final long[] total = {0};
        database.forEachValueByPrefix(prefix, value -> total[0] = Math.addExact(total[0], decodeSize(value)));
        return total[0];
    }
    private static byte[] encodeSize(long size) { return ByteBuffer.allocate(Long.BYTES).putLong(size).array(); }
    private static long decodeSize(byte[] value) {
        if (value.length != Long.BYTES) throw new IllegalStateException("Invalid prune usage size metadata");
        long size = ByteBuffer.wrap(value).getLong();
        if (size < 0) throw new IllegalStateException("Negative prune usage size metadata");
        return size;
    }
    private static byte[] key(byte prefix, Hash256 hash) {
        byte[] raw = hash.bytes();
        if (raw.length != HASH_SIZE) throw new IllegalStateException("Invalid block hash length");
        byte[] key = new byte[1 + HASH_SIZE]; key[0] = prefix; System.arraycopy(raw, 0, key, 1, HASH_SIZE); return key;
    }
}
