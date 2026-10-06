package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.MuHash3072;

public final class RocksDbUtxoStore
        implements UtxoStore {

    private static final byte DEFAULT_UTXO_PREFIX = 0x03;

    /** Diagnostic full count; requires a stable chain snapshot. */
    public long count() { return database.countPrefix(prefix); }

    private static final int TXID_SIZE = 32;
    private static final int VOUT_SIZE = 4;

    private final RocksDbDatabase database;
    private volatile byte prefix;

    /*
     * Bounded chainstate read cache. Both present and absent states are cached.
     * Absence is safe because every mutation through this store publishes the new
     * state after commit: save/created replaces the tombstone, spend/delete installs
     * one, and namespace switches clear the cache. This avoids repeated RocksDB reads
     * for the same missing/BIP30-probed OutPoint during linear IBD.
     */
    private final int readCacheCapacity;
    private final Map<OutPoint, Optional<StoredUtxo>> readCache;
    private long cacheHits;
    private long cacheMisses;

    public RocksDbUtxoStore(
            RocksDbDatabase database
    ) {
        this(database, DEFAULT_UTXO_PREFIX, 0);
    }

    /** Creates the same UTXO store layout under an isolated first-byte namespace. */
    public RocksDbUtxoStore(RocksDbDatabase database, byte prefix) {
        this(database, prefix, 0);
    }

    /**
     * Creates a UTXO store with an optional bounded positive read cache.
     * A capacity of zero preserves the historical uncached behaviour.
     */
    public RocksDbUtxoStore(RocksDbDatabase database, byte prefix, int readCacheCapacity) {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        if (readCacheCapacity < 0) throw new IllegalArgumentException("readCacheCapacity must not be negative");
        this.database = database;
        this.prefix = prefix;
        this.readCacheCapacity = readCacheCapacity;
        this.readCache = readCacheCapacity == 0 ? null : new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<OutPoint, Optional<StoredUtxo>> eldest) {
                return size() > RocksDbUtxoStore.this.readCacheCapacity;
            }
        };
    }

    public byte namespacePrefix() { return prefix; }

    /** Speculative disk-cache warming never publishes entries in the mutable coins cache. */
    public void warmPersistentInputs(Collection<OutPoint> outPoints) {
        List<byte[]> keys = new ArrayList<>();
        synchronized (this) {
            for (OutPoint outPoint : outPoints) {
                if (readCache == null || !readCache.containsKey(outPoint)) keys.add(key(outPoint));
            }
        }
        database.warmKeys(keys);
    }

    /** Switches this already-wired store to another isolated UTXO namespace. */
    public synchronized void activateNamespace(byte prefix) {
        this.prefix = prefix;
        clearReadCache();
    }

    public synchronized CacheStats cacheStats() {
        return new CacheStats(cacheHits, cacheMisses, readCache == null ? 0 : readCache.size(), readCacheCapacity);
    }

    public record CacheStats(long hits, long misses, int size, int capacity) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0d : (double) hits / (double) total;
        }
    }

    public synchronized void clearReadCache() {
        if (readCache != null) readCache.clear();
    }

    private synchronized Optional<StoredUtxo> cached(OutPoint outPoint) {
        if (readCache == null || !readCache.containsKey(outPoint)) {
            cacheMisses++;
            return null;
        }
        cacheHits++;
        return readCache.get(outPoint);
    }

    private synchronized void cache(OutPoint outPoint, Optional<StoredUtxo> utxo) {
        if (readCache != null) readCache.put(outPoint, utxo);
    }

    private synchronized void cachePresent(OutPoint outPoint, StoredUtxo utxo) {
        cache(outPoint, Optional.of(utxo));
    }

    private synchronized void cacheAbsent(OutPoint outPoint) {
        cache(outPoint, Optional.empty());
    }

    private synchronized void invalidate(OutPoint outPoint) {
        if (readCache != null) readCache.remove(outPoint);
    }

    /** Updates the cache only after the corresponding atomic chain transition committed. */
    public void applyCommittedChanges(UtxoChanges changes) {
        if (changes == null || readCache == null) return;
        synchronized (this) {
            for (OutPoint spent : changes.spentOutputs()) readCache.put(spent, Optional.empty());
            for (CreatedUtxo created : changes.createdOutputs()) readCache.put(created.outPoint(), Optional.of(created.utxo()));
        }
    }

    @Override
    public void save(
            OutPoint outPoint,
            StoredUtxo utxo
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        database.put(
                key(outPoint),
                StoredUtxoSerializer.serialize(
                        utxo
                )
        );
        cachePresent(outPoint, utxo);
    }

    @Override
    public Optional<StoredUtxo> find(
            OutPoint outPoint
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        Optional<StoredUtxo> cached = cached(outPoint);
        if (cached != null) return cached;

        byte[] value = database.get(key(outPoint));
        if (value == null) {
            cacheAbsent(outPoint);
            return Optional.empty();
        }

        StoredUtxo restored = StoredUtxoSerializer.deserialize(value);
        cachePresent(outPoint, restored);
        return Optional.of(restored);
    }

    @Override
    public Map<OutPoint, Optional<StoredUtxo>> findAll(
            Collection<OutPoint> outPoints
    ) {
        if (outPoints == null) throw new IllegalArgumentException("outPoints must not be null");

        Map<OutPoint, Optional<StoredUtxo>> result = new LinkedHashMap<>();
        List<OutPoint> misses = new ArrayList<>();
        List<byte[]> keys = new ArrayList<>();

        for (OutPoint outPoint : outPoints) {
            if (outPoint == null) throw new IllegalArgumentException("outPoints must not contain null");
            if (result.containsKey(outPoint)) continue;

            Optional<StoredUtxo> cached = cached(outPoint);
            if (cached != null) {
                result.put(outPoint, cached);
            } else {
                misses.add(outPoint);
                keys.add(key(outPoint));
                // Reserve insertion order. Filled after MultiGet.
                result.put(outPoint, Optional.empty());
            }
        }

        if (!keys.isEmpty()) {
            List<byte[]> values = database.getAll(keys);
            if (values.size() != misses.size()) {
                throw new IllegalStateException("RocksDB MultiGet returned unexpected result count");
            }
            for (int i = 0; i < misses.size(); i++) {
                byte[] value = values.get(i);
                OutPoint outPoint = misses.get(i);
                if (value == null) {
                    cacheAbsent(outPoint);
                    continue;
                }
                StoredUtxo restored = StoredUtxoSerializer.deserialize(value);
                cachePresent(outPoint, restored);
                result.put(outPoint, Optional.of(restored));
            }
        }
        return result;
    }

    @Override
    public void delete(
            OutPoint outPoint
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        database.delete(
                key(outPoint)
        );
        cacheAbsent(outPoint);
    }

    private byte[] key(
            OutPoint outPoint
    ) {
        byte[] txid =
                outPoint.transactionId().bytes();

        if (txid.length != TXID_SIZE) {
            throw new IllegalStateException(
                    "Invalid txid length: "
                            + txid.length
            );
        }

        byte[] key =
                new byte[
                        1
                                + TXID_SIZE
                                + VOUT_SIZE
                        ];

        key[0] =
                prefix;

        System.arraycopy(
                txid,
                0,
                key,
                1,
                TXID_SIZE
        );

        long index =
                outPoint.outputIndex().value();

        int offset =
                1 + TXID_SIZE;

        key[offset] =
                (byte) (
                        index & 0xFF
                );

        key[offset + 1] =
                (byte) (
                        (index >>> 8) & 0xFF
                );

        key[offset + 2] =
                (byte) (
                        (index >>> 16) & 0xFF
                );

        key[offset + 3] =
                (byte) (
                        (index >>> 24) & 0xFF
                );

        return key;
    }
    public void save(
            RocksDbWriteBatch batch,
            OutPoint outPoint,
            StoredUtxo utxo
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        batch.put(
                key(outPoint),
                StoredUtxoSerializer.serialize(
                        utxo
                )
        );
        // Staged batch is not visible until the atomic transition commits.
        invalidate(outPoint);
    }

    public void delete(
            RocksDbWriteBatch batch,
            OutPoint outPoint
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        batch.delete(
                key(outPoint)
        );
        // Do not publish speculative absence before the batch commit.
        invalidate(outPoint);
    }
    /** Removes the complete persistent namespace in the caller's atomic batch. */
    public void clear(RocksDbWriteBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.deletePrefix(prefix);
        clearReadCache();
    }

    /**
     * Computes Core-compatible chainstate statistics while the caller holds the chainstate lock.
     * hashSerialized3 commits to each outpoint, height/coinbase metadata and TxOut exactly as
     * Bitcoin Core's kernel/coinstats.cpp TxOutSer/ApplyHash path does. The scan is streaming by
     * txid; only one transaction's unspent outputs are retained so vout can be sorted numerically.
     */
    public enum HashType { HASH_SERIALIZED_3, MUHASH, NONE }

    public Statistics statistics() { return statistics(HashType.HASH_SERIALIZED_3); }

    public Statistics statistics(boolean includeHashSerialized3) {
        return statistics(includeHashSerialized3 ? HashType.HASH_SERIALIZED_3 : HashType.NONE);
    }

    public Statistics statistics(HashType hashType) {
        final long[] transactions = {0L};
        final long[] txouts = {0L};
        final long[] totalAmount = {0L};
        final long[] bogoSize = {0L};
        final byte[][] currentTxid = {null};
        final TreeMap<Long, StoredUtxo> outputs = new TreeMap<>();
        if (hashType == null) throw new IllegalArgumentException("hashType must not be null");
        final MessageDigest first = hashType == HashType.HASH_SERIALIZED_3 ? sha256() : null;
        final MuHash3072 muhash = hashType == HashType.MUHASH ? new MuHash3072() : null;

        database.forEachEntryByPrefix(prefix, (key, value) -> {
            if (key.length != 1 + TXID_SIZE + VOUT_SIZE)
                throw new IllegalStateException("Invalid UTXO key length: " + key.length);
            byte[] txid = Arrays.copyOfRange(key, 1, 1 + TXID_SIZE);
            if (currentTxid[0] != null && !Arrays.equals(currentTxid[0], txid)) {
                applyTransaction(first, muhash, currentTxid[0], outputs, transactions, txouts, totalAmount, bogoSize);
                outputs.clear();
            }
            currentTxid[0] = txid;
            long vout = readUInt32LittleEndian(key, 1 + TXID_SIZE);
            StoredUtxo previous = outputs.put(vout, StoredUtxoSerializer.deserialize(value));
            if (previous != null) throw new IllegalStateException("Duplicate UTXO outpoint in persistent store");
        });
        if (currentTxid[0] != null)
            applyTransaction(first, muhash, currentTxid[0], outputs, transactions, txouts, totalAmount, bogoSize);

        Hash256 hashSerialized3 = first == null ? null : new Hash256(sha256().digest(first.digest()));
        Hash256 muhashDigest = muhash == null ? null : muhash.finalizeHash();
        return new Statistics(transactions[0], txouts[0], bogoSize[0],
                database.valueBytesByPrefix(prefix), totalAmount[0], hashSerialized3, muhashDigest);
    }

    private static void applyTransaction(MessageDigest digest, MuHash3072 muhash, byte[] txid, TreeMap<Long, StoredUtxo> outputs,
                                         long[] transactions, long[] txouts, long[] totalAmount, long[] bogoSize) {
        if (outputs.isEmpty()) return;
        transactions[0] = Math.incrementExact(transactions[0]);
        for (var entry : outputs.entrySet()) {
            long vout = entry.getKey();
            StoredUtxo coin = entry.getValue();
            if (coin.height() > 0x7fff_ffffL)
                throw new IllegalStateException("UTXO height cannot be encoded by hash_serialized_3: " + coin.height());
            byte[] script = coin.scriptPubKey();
            if (digest != null || muhash != null) {
                byte[] serialized = CoreCoinStatsSerializer.serialize(txid, vout, coin);
                if (digest != null) digest.update(serialized);
                if (muhash != null) muhash.insert(serialized);
            }
            txouts[0] = Math.incrementExact(txouts[0]);
            totalAmount[0] = Math.addExact(totalAmount[0], coin.amount());
            bogoSize[0] = Math.addExact(bogoSize[0], 50L + script.length);
        }
    }


    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static long readUInt32LittleEndian(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL)
                | (((long) bytes[offset + 1] & 0xffL) << 8)
                | (((long) bytes[offset + 2] & 0xffL) << 16)
                | (((long) bytes[offset + 3] & 0xffL) << 24);
    }

    private static void updateUInt32LittleEndian(MessageDigest digest, long value) {
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("uint32 out of range");
        for (int i = 0; i < 4; i++) digest.update((byte) (value >>> (8 * i)));
    }

    private static void updateInt64LittleEndian(MessageDigest digest, long value) {
        for (int i = 0; i < 8; i++) digest.update((byte) (value >>> (8 * i)));
    }

    public record Statistics(long transactions, long txouts, long bogoSize, long diskSize, long totalAmount,
                             Hash256 hashSerialized3, Hash256 muhash) {}

}
