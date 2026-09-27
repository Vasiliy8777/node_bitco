package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;
import java.util.Arrays;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.MuHash3072;
import java.io.ByteArrayOutputStream;

public final class RocksDbUtxoStore
        implements UtxoStore {

    private static final byte UTXO_PREFIX = 0x03;

    /** Diagnostic full count; requires a stable chain snapshot. */
    public long count() { return database.countPrefix(UTXO_PREFIX); }

    private static final int TXID_SIZE = 32;
    private static final int VOUT_SIZE = 4;

    private final RocksDbDatabase database;

    public RocksDbUtxoStore(
            RocksDbDatabase database
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        this.database = database;
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

        byte[] value =
                database.get(
                        key(outPoint)
                );

        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(
                StoredUtxoSerializer.deserialize(
                        value
                )
        );
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
    }

    private static byte[] key(
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
                UTXO_PREFIX;

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
    }
    /** Removes the complete persistent namespace in the caller's atomic batch. */
    public void clear(RocksDbWriteBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.deletePrefix(UTXO_PREFIX);
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

        database.forEachEntryByPrefix(UTXO_PREFIX, (key, value) -> {
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
                database.valueBytesByPrefix(UTXO_PREFIX), totalAmount[0], hashSerialized3, muhashDigest);
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
                byte[] serialized = serializeCoin(txid, vout, coin, script);
                if (digest != null) digest.update(serialized);
                if (muhash != null) muhash.insert(serialized);
            }
            txouts[0] = Math.incrementExact(txouts[0]);
            totalAmount[0] = Math.addExact(totalAmount[0], coin.amount());
            bogoSize[0] = Math.addExact(bogoSize[0], 50L + script.length);
        }
    }


    private static byte[] serializeCoin(byte[] txid, long vout, StoredUtxo coin, byte[] script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 + script.length);
        out.writeBytes(txid);
        writeUInt32LittleEndian(out, vout);
        writeUInt32LittleEndian(out, Math.addExact(Math.multiplyExact(coin.height(), 2L), coin.coinbase() ? 1L : 0L));
        writeInt64LittleEndian(out, coin.amount());
        out.writeBytes(CompactSize.encode(script.length));
        out.writeBytes(script);
        return out.toByteArray();
    }

    private static void writeUInt32LittleEndian(ByteArrayOutputStream out, long value) {
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("uint32 out of range");
        for (int i = 0; i < 4; i++) out.write((byte) (value >>> (8 * i)));
    }

    private static void writeInt64LittleEndian(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((byte) (value >>> (8 * i)));
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
