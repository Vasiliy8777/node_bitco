package ru.bitcoin.node.storage.undo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;

import java.util.Optional;

public final class RocksDbUndoStore
        implements UndoStore {

    private static final byte UNDO_PREFIX = 0x04;

    private static final int HASH_SIZE = 32;

    private final RocksDbDatabase database;

    public RocksDbUndoStore(
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
            Hash256 blockHash,
            BlockUndoData undoData
    ) {
        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            save(batch, blockHash, undoData);
            database.write(batch);
        }
    }

    @Override
    public Optional<BlockUndoData> find(
            Hash256 blockHash
    ) {
        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        byte[] value =
                database.get(
                        key(blockHash)
                );

        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(
                BlockUndoDataSerializer.deserialize(
                        value
                )
        );
    }

    public long serializedSize(Hash256 blockHash) {
        if (blockHash == null) throw new IllegalArgumentException("blockHash must not be null");
        return new RocksDbPruneUsageStore(database).undoSize(blockHash);
    }

    @Override
    public void delete(
            Hash256 blockHash
    ) {
        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            delete(batch, blockHash);
            database.write(batch);
        }
    }

    private static byte[] key(
            Hash256 blockHash
    ) {
        byte[] hashBytes =
                blockHash.bytes();

        if (hashBytes.length != HASH_SIZE) {
            throw new IllegalStateException(
                    "Invalid block hash length: "
                            + hashBytes.length
            );
        }

        byte[] key =
                new byte[
                        1 + HASH_SIZE
                        ];

        key[0] =
                UNDO_PREFIX;

        System.arraycopy(
                hashBytes,
                0,
                key,
                1,
                HASH_SIZE
        );

        return key;
    }
    public void save(
            RocksDbWriteBatch batch,
            Hash256 blockHash,
            BlockUndoData undoData
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }

        byte[] serialized = BlockUndoDataSerializer.serialize(undoData);
        batch.put(key(blockHash), serialized);
        new RocksDbBlockAvailabilityStore(database).markUndo(batch, blockHash);
        new RocksDbPruneUsageStore(database).setUndoSize(batch, blockHash, serialized.length);
    }

    public void delete(
            RocksDbWriteBatch batch,
            Hash256 blockHash
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        batch.delete(key(blockHash));
        new RocksDbBlockAvailabilityStore(database).clearUndo(batch, blockHash);
        new RocksDbPruneUsageStore(database).setUndoSize(batch, blockHash, 0L);
    }
    /** Removes the complete persistent namespace in the caller's atomic batch. */
    public void clear(RocksDbWriteBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.deletePrefix(UNDO_PREFIX);
        new RocksDbPruneUsageStore(database).clearUndoSizes(batch);
    }

}