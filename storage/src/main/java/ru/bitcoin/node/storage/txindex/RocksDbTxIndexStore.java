package ru.bitcoin.node.storage.txindex;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;
import java.util.Optional;

/** Persistent transaction-id to containing-block index plus a crash-recovery sync cursor. */
public final class RocksDbTxIndexStore {
    public static final byte TX_PREFIX = 0x0e;
    private static final byte[] BEST_BLOCK_KEY = {(byte) 0x0f, 0x01};
    private final RocksDbDatabase database;

    public RocksDbTxIndexStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<Hash256> findBlockHash(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        byte[] value = database.get(txKey(txid));
        if (value == null) return Optional.empty();
        if (value.length != Hash256.LENGTH) throw new IllegalStateException("Invalid txindex block hash length: " + value.length);
        return Optional.of(new Hash256(value));
    }

    public Optional<Hash256> bestIndexedBlockHash() {
        byte[] value = database.get(BEST_BLOCK_KEY);
        if (value == null) return Optional.empty();
        if (value.length != Hash256.LENGTH) throw new IllegalStateException("Invalid txindex best-block hash length: " + value.length);
        return Optional.of(new Hash256(value));
    }

    /** Atomically indexes one active-chain block and advances the durable sync cursor. */
    public void append(Block block) {
        Objects.requireNonNull(block, "block");
        try (var batch = new RocksDbWriteBatch()) {
            for (var tx : block.transactions()) batch.put(txKey(tx.txId()), block.hash().bytes());
            batch.put(BEST_BLOCK_KEY, block.hash().bytes());
            database.write(batch);
        }
    }

    /** Initializes the cursor at genesis. Bitcoin Core deliberately does not index genesis transactions. */
    public void initializeAt(Hash256 genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        try (var batch = new RocksDbWriteBatch()) {
            batch.put(BEST_BLOCK_KEY, genesisHash.bytes());
            database.write(batch);
        }
    }

    public void clear() {
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(TX_PREFIX);
            batch.delete(BEST_BLOCK_KEY);
            database.write(batch);
        }
    }

    private static byte[] txKey(Hash256 txid) {
        byte[] hash = txid.bytes();
        byte[] key = new byte[1 + hash.length];
        key[0] = TX_PREFIX;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }
}
