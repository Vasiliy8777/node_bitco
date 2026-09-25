package ru.bitcoin.node.storage.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.*;

/** Durable local mempool snapshot. Entries are keyed by txid and revalidated on startup. */
public final class RocksDbMempoolStore {
    public static final byte PREFIX = 0x0d;
    private final RocksDbDatabase database;

    public RocksDbMempoolStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public List<Transaction> load() {
        List<Transaction> result = new ArrayList<>();
        for (byte[] value : database.valuesByPrefix(PREFIX)) {
            try { result.add(TransactionParser.parse(value)); }
            catch (IllegalArgumentException ignored) { /* corrupt/stale entry is discarded by next sync */ }
        }
        return List.copyOf(result);
    }

    /** Atomically makes durable membership equal to the supplied in-memory snapshots' delta. */
    public void apply(Collection<Transaction> before, Collection<Transaction> after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<Hash256, Transaction> oldEntries = transactions(before);
        Map<Hash256, Transaction> newEntries = transactions(after);
        try (var batch = new RocksDbWriteBatch()) {
            for (Hash256 id : oldEntries.keySet()) if (!newEntries.containsKey(id)) batch.delete(key(id));
            for (var entry : newEntries.entrySet()) if (!oldEntries.containsKey(entry.getKey())) {
                batch.put(key(entry.getKey()), TransactionSerializer.serialize(entry.getValue()));
            }
            database.write(batch);
        }
    }

    public void replace(Collection<Transaction> entries) {
        Objects.requireNonNull(entries, "entries");
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(PREFIX);
            for (Transaction transaction : entries) {
                batch.put(key(transaction.txId()), TransactionSerializer.serialize(transaction));
            }
            database.write(batch);
        }
    }

    private static Map<Hash256, Transaction> transactions(Collection<Transaction> entries) {
        Map<Hash256, Transaction> result = new LinkedHashMap<>();
        for (Transaction transaction : entries) result.put(transaction.txId(), transaction);
        return result;
    }

    private static byte[] key(Hash256 txid) {
        byte[] hash = txid.bytes();
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }
}
