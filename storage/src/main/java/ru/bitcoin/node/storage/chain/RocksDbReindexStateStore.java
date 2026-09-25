package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;
import java.util.Optional;

/** Durable crash marker for an in-progress chainstate rebuild. */
public final class RocksDbReindexStateStore {

    private static final byte[] TARGET_TIP_KEY = {0x0A, 0x01};
    private static final int HASH_SIZE = Hash256.LENGTH;

    private final RocksDbDatabase database;

    public RocksDbReindexStateStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<Hash256> loadTargetTipHash() {
        byte[] value = database.get(TARGET_TIP_KEY);
        if (value == null) return Optional.empty();
        if (value.length != HASH_SIZE) {
            throw new IllegalStateException("Invalid chainstate reindex target hash size: " + value.length);
        }
        return Optional.of(new Hash256(value));
    }

    public void saveTargetTipHash(RocksDbWriteBatch batch, Hash256 hash) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(hash, "hash");
        batch.put(TARGET_TIP_KEY, hash.bytes());
    }

    public void clear(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.delete(TARGET_TIP_KEY);
    }
}
