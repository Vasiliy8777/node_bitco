package ru.bitcoin.node.storage.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.util.*;

/** Durable local mempool snapshot. Entries are keyed by txid and revalidated on startup. */
public final class RocksDbMempoolStore {
    public static final byte PREFIX = 0x0d;
    private static final byte[] FORMAT_MAGIC = new byte[]{'M', 'P', 'V', '2'};
    private final RocksDbDatabase database;

    public RocksDbMempoolStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public List<PersistedMempoolEntry> load() {
        List<PersistedMempoolEntry> result = new ArrayList<>();
        for (byte[] value : database.valuesByPrefix(PREFIX)) {
            try { result.add(decode(value)); }
            catch (IllegalArgumentException ignored) { /* corrupt/stale entry is discarded by next sync */ }
        }
        return List.copyOf(result);
    }

    /** Atomically makes durable membership and entry-time metadata equal to the supplied snapshots' delta. */
    public void apply(Collection<PersistedMempoolEntry> before, Collection<PersistedMempoolEntry> after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<Hash256, PersistedMempoolEntry> oldEntries = entries(before);
        Map<Hash256, PersistedMempoolEntry> newEntries = entries(after);
        try (var batch = new RocksDbWriteBatch()) {
            for (Hash256 id : oldEntries.keySet()) if (!newEntries.containsKey(id)) batch.delete(key(id));
            for (var entry : newEntries.entrySet()) {
                PersistedMempoolEntry old = oldEntries.get(entry.getKey());
                if (old == null || old.arrivalTime() != entry.getValue().arrivalTime()
                        || !old.transaction().wtxId().equals(entry.getValue().transaction().wtxId())) {
                    batch.put(key(entry.getKey()), encode(entry.getValue()));
                }
            }
            database.write(batch);
        }
    }

    public void replace(Collection<PersistedMempoolEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(PREFIX);
            for (PersistedMempoolEntry entry : entries) {
                batch.put(key(entry.transaction().txId()), encode(entry));
            }
            database.write(batch);
        }
    }

    private static byte[] encode(PersistedMempoolEntry entry) {
        byte[] tx = TransactionSerializer.serialize(entry.transaction());
        ByteBuffer buffer = ByteBuffer.allocate(FORMAT_MAGIC.length + Long.BYTES + tx.length);
        buffer.put(FORMAT_MAGIC);
        buffer.putLong(entry.arrivalTime());
        buffer.put(tx);
        return buffer.array();
    }

    private static PersistedMempoolEntry decode(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (hasMagic(value)) {
            if (value.length <= FORMAT_MAGIC.length + Long.BYTES) throw new IllegalArgumentException("Truncated mempool entry");
            ByteBuffer buffer = ByteBuffer.wrap(value);
            buffer.position(FORMAT_MAGIC.length);
            long arrivalTime = buffer.getLong();
            if (arrivalTime < 0) throw new IllegalArgumentException("Negative mempool arrival time");
            byte[] tx = new byte[buffer.remaining()];
            buffer.get(tx);
            return new PersistedMempoolEntry(TransactionParser.parse(tx), arrivalTime);
        }
        // Backward compatibility with the original transaction-only RocksDB format.
        return new PersistedMempoolEntry(TransactionParser.parse(value), 0L);
    }

    private static boolean hasMagic(byte[] value) {
        if (value.length < FORMAT_MAGIC.length) return false;
        for (int i = 0; i < FORMAT_MAGIC.length; i++) if (value[i] != FORMAT_MAGIC[i]) return false;
        return true;
    }

    private static Map<Hash256, PersistedMempoolEntry> entries(Collection<PersistedMempoolEntry> entries) {
        Map<Hash256, PersistedMempoolEntry> result = new LinkedHashMap<>();
        for (PersistedMempoolEntry entry : entries) result.put(entry.transaction().txId(), entry);
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
