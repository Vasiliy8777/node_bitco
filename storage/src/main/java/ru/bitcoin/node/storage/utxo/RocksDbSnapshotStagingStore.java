package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

/**
 * Isolated, non-active UTXO namespace used while a snapshot is being validated.
 */
public final class RocksDbSnapshotStagingStore implements UtxoStore {
    private static final byte PREFIX = RocksDbNamespaces.SNAPSHOT_UTXO_STAGING;
    private final RocksDbDatabase db;

    public RocksDbSnapshotStagingStore(RocksDbDatabase db) {
        this.db = java.util.Objects.requireNonNull(db);
    }

    @Override
    public void save(OutPoint o, StoredUtxo u) {
        db.put(key(o), StoredUtxoSerializer.serialize(u));
    }

    @Override
    public Optional<StoredUtxo> find(OutPoint o) {
        byte[] v = db.get(key(o));
        return v == null ? Optional.empty() : Optional.of(StoredUtxoSerializer.deserialize(v));
    }

    @Override
    public void delete(OutPoint o) {
        db.delete(key(o));
    }

    public long count() {
        return db.countPrefix(PREFIX);
    }

    public void clear() {
        try (var b = new RocksDbWriteBatch()) {
            b.deletePrefix(PREFIX);
            db.write(b);
        }
    }

    private static byte[] key(OutPoint o) {
        byte[] k = new byte[37], h = o.transactionId().bytes();
        k[0] = PREFIX;
        System.arraycopy(h, 0, k, 1, 32);
        long n = o.outputIndex().value();
        for (int i = 0; i < 4; i++) k[33 + i] = (byte) (n >>> (8 * i));
        return k;
    }
}
