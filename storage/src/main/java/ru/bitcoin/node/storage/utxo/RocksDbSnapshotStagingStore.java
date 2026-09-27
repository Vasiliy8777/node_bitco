package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;
import java.util.Arrays;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.MuHash3072;

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

    /** Core-compatible statistics over the isolated staging namespace. */
    public RocksDbUtxoStore.Statistics statistics(RocksDbUtxoStore.HashType hashType) {
        if (hashType == null) throw new IllegalArgumentException("hashType must not be null");
        final long[] transactions = {0}, txouts = {0}, totalAmount = {0}, bogoSize = {0};
        final byte[][] currentTxid = {null};
        final TreeMap<Long, StoredUtxo> outputs = new TreeMap<>();
        final MessageDigest first = hashType == RocksDbUtxoStore.HashType.HASH_SERIALIZED_3 ? sha256() : null;
        final MuHash3072 muhash = hashType == RocksDbUtxoStore.HashType.MUHASH ? new MuHash3072() : null;
        db.forEachEntryByPrefix(PREFIX, (key, value) -> {
            if (key.length != 37) throw new IllegalStateException("Invalid staged UTXO key length: " + key.length);
            byte[] txid = Arrays.copyOfRange(key, 1, 33);
            if (currentTxid[0] != null && !Arrays.equals(currentTxid[0], txid)) {
                apply(first, muhash, currentTxid[0], outputs, transactions, txouts, totalAmount, bogoSize);
                outputs.clear();
            }
            currentTxid[0] = txid;
            long vout = ((long) key[33] & 255) | (((long) key[34] & 255) << 8) | (((long) key[35] & 255) << 16) | (((long) key[36] & 255) << 24);
            if (outputs.put(vout, StoredUtxoSerializer.deserialize(value)) != null)
                throw new IllegalStateException("Duplicate staged UTXO outpoint");
        });
        if (currentTxid[0] != null) apply(first, muhash, currentTxid[0], outputs, transactions, txouts, totalAmount, bogoSize);
        Hash256 serialized = first == null ? null : new Hash256(sha256().digest(first.digest()));
        Hash256 muhashDigest = muhash == null ? null : muhash.finalizeHash();
        return new RocksDbUtxoStore.Statistics(transactions[0], txouts[0], bogoSize[0],
                db.valueBytesByPrefix(PREFIX), totalAmount[0], serialized, muhashDigest);
    }

    private static void apply(MessageDigest digest, MuHash3072 muhash, byte[] txid, TreeMap<Long, StoredUtxo> outputs,
                              long[] transactions, long[] txouts, long[] totalAmount, long[] bogoSize) {
        if (outputs.isEmpty()) return;
        transactions[0] = Math.incrementExact(transactions[0]);
        for (var entry : outputs.entrySet()) {
            StoredUtxo coin = entry.getValue();
            byte[] serialized = (digest != null || muhash != null)
                    ? CoreCoinStatsSerializer.serialize(txid, entry.getKey(), coin) : null;
            if (digest != null) digest.update(serialized);
            if (muhash != null) muhash.insert(serialized);
            txouts[0] = Math.incrementExact(txouts[0]);
            totalAmount[0] = Math.addExact(totalAmount[0], coin.amount());
            bogoSize[0] = Math.addExact(bogoSize[0], 50L + coin.scriptPubKey().length);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
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
