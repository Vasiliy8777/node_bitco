package ru.bitcoin.node.storage.txospender;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/** Persistent active-chain outpoint -> spending transaction/block index. */
public final class RocksDbTxOutSpenderIndexStore {
    private static final byte PREFIX = RocksDbNamespaces.TXO_SPENDER_INDEX;
    private static final byte[] BEST_BLOCK_KEY = RocksDbNamespaces.singletonKey(RocksDbNamespaces.TXO_SPENDER_INDEX_STATE);
    private static final int VALUE_LENGTH = Hash256.LENGTH * 2;
    private final RocksDbDatabase database;

    public RocksDbTxOutSpenderIndexStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<Spender> find(OutPoint outPoint) {
        Objects.requireNonNull(outPoint, "outPoint");
        byte[] value = database.get(key(outPoint));
        if (value == null) return Optional.empty();
        if (value.length != VALUE_LENGTH) throw new IllegalStateException("Invalid txospenderindex record length: " + value.length);
        byte[] txid = new byte[Hash256.LENGTH];
        byte[] blockHash = new byte[Hash256.LENGTH];
        System.arraycopy(value, 0, txid, 0, Hash256.LENGTH);
        System.arraycopy(value, Hash256.LENGTH, blockHash, 0, Hash256.LENGTH);
        return Optional.of(new Spender(new Hash256(txid), new Hash256(blockHash)));
    }

    public Optional<Hash256> bestIndexedBlockHash() {
        byte[] value = database.get(BEST_BLOCK_KEY);
        if (value == null) return Optional.empty();
        if (value.length != Hash256.LENGTH) throw new IllegalStateException("Invalid txospenderindex cursor length: " + value.length);
        return Optional.of(new Hash256(value));
    }

    public void append(Block block) {
        Objects.requireNonNull(block, "block");
        try (var batch = new RocksDbWriteBatch()) {
            for (var tx : block.transactions()) {
                if (tx.isCoinbase()) continue;
                byte[] value = new byte[VALUE_LENGTH];
                System.arraycopy(tx.txId().bytes(), 0, value, 0, Hash256.LENGTH);
                System.arraycopy(block.hash().bytes(), 0, value, Hash256.LENGTH, Hash256.LENGTH);
                for (var input : tx.inputs()) batch.put(key(input.previousOutput()), value);
            }
            batch.put(BEST_BLOCK_KEY, block.hash().bytes());
            database.write(batch);
        }
    }

    public void rewind(Block disconnectedBlock, Hash256 newBestBlockHash) {
        Objects.requireNonNull(disconnectedBlock, "disconnectedBlock");
        Objects.requireNonNull(newBestBlockHash, "newBestBlockHash");
        try (var batch = new RocksDbWriteBatch()) {
            for (var tx : disconnectedBlock.transactions()) {
                if (tx.isCoinbase()) continue;
                for (var input : tx.inputs()) {
                    Spender current = find(input.previousOutput()).orElse(null);
                    if (current != null && current.blockHash().equals(disconnectedBlock.hash())
                            && current.transactionId().equals(tx.txId())) batch.delete(key(input.previousOutput()));
                }
            }
            batch.put(BEST_BLOCK_KEY, newBestBlockHash.bytes());
            database.write(batch);
        }
    }

    public void initializeAt(Hash256 genesisHash) {
        try (var batch = new RocksDbWriteBatch()) {
            batch.put(BEST_BLOCK_KEY, Objects.requireNonNull(genesisHash, "genesisHash").bytes());
            database.write(batch);
        }
    }

    public void clear() {
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(PREFIX);
            batch.delete(BEST_BLOCK_KEY);
            database.write(batch);
        }
    }

    private static byte[] key(OutPoint outPoint) {
        byte[] hash = outPoint.transactionId().bytes();
        ByteBuffer buffer = ByteBuffer.allocate(1 + Hash256.LENGTH + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(PREFIX).put(hash).putInt((int) outPoint.outputIndex().value());
        return buffer.array();
    }

    public record Spender(Hash256 transactionId, Hash256 blockHash) {
        public Spender {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(blockHash, "blockHash");
        }
    }
}
