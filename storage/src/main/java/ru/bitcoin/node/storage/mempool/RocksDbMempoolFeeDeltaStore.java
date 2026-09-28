package ru.bitcoin.node.storage.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable Bitcoin Core-style local fee deltas, including txids not currently in mempool. */
public final class RocksDbMempoolFeeDeltaStore {
    private final RocksDbDatabase database;
    public RocksDbMempoolFeeDeltaStore(RocksDbDatabase database) { this.database = Objects.requireNonNull(database); }

    public Map<Hash256, Long> load() {
        Map<Hash256, Long> result = new LinkedHashMap<>();
        database.forEachEntryByPrefix(RocksDbNamespaces.MEMPOOL_FEE_DELTA, (key, value) -> {
            if (key.length != 33 || value.length != Long.BYTES) return;
            byte[] hash = new byte[32]; System.arraycopy(key, 1, hash, 0, 32);
            long delta = ByteBuffer.wrap(value).getLong();
            if (delta != 0) result.put(new Hash256(hash), delta);
        });
        return Map.copyOf(result);
    }

    public void replace(Map<Hash256, Long> deltas) {
        Objects.requireNonNull(deltas);
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(RocksDbNamespaces.MEMPOOL_FEE_DELTA);
            deltas.forEach((txid, delta) -> { if (delta != 0) batch.put(key(txid), ByteBuffer.allocate(8).putLong(delta).array()); });
            database.write(batch);
        }
    }

    private static byte[] key(Hash256 txid) {
        byte[] hash = txid.bytes(); byte[] key = new byte[33]; key[0] = RocksDbNamespaces.MEMPOOL_FEE_DELTA;
        System.arraycopy(hash, 0, key, 1, 32); return key;
    }
}
