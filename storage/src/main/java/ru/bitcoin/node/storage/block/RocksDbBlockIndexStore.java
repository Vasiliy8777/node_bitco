package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

public final class RocksDbBlockIndexStore
        implements BlockIndexStore {
    private static final int HASH_SIZE = 32;
    private static final byte BLOCK_INDEX_PREFIX = 0x01;

    private final RocksDbDatabase database;

    public RocksDbBlockIndexStore(
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
            StoredBlockIndex blockIndex
    ) {
        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        database.put(
                key(blockIndex.hash()),
                StoredBlockIndexSerializer.serialize(
                        blockIndex
                )
        );
    }

    @Override
    public Optional<StoredBlockIndex> find(
            Hash256 hash
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        byte[] value =
                database.get(
                        key(hash)
                );

        if (value == null) {
            return Optional.empty();
        }

        /*return Optional.of(
                StoredBlockIndexSerializer.deserialize(
                        value
                )
        );*/
        StoredBlockIndex blockIndex =
                StoredBlockIndexSerializer.deserialize(
                        value
                );

        if (!blockIndex.hash().equals(hash)) {
            throw new IllegalStateException(
                    "Stored block index hash mismatch. "
                            + "Expected: "
                            + hash.toDisplayHex()
                            + ", actual: "
                            + blockIndex.hash().toDisplayHex()
            );
        }

        return Optional.of(
                blockIndex
        );
    }

    @Override
    public void delete(
            Hash256 hash
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        database.delete(
                key(hash)
        );
    }

    private static byte[] key(
            Hash256 hash
    ) {
        byte[] hashBytes =
                hash.bytes();

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
                BLOCK_INDEX_PREFIX;

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
            StoredBlockIndex blockIndex
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        batch.put(
                key(blockIndex.hash()),
                StoredBlockIndexSerializer.serialize(
                        blockIndex
                )
        );
    }

    public void delete(
            RocksDbWriteBatch batch,
            Hash256 hash
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        batch.delete(
                key(hash)
        );
    }
}