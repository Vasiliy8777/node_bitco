package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

public final class RocksDbBlockStore
        implements BlockStore {

    private static final byte BLOCK_PREFIX = 0x05;

    private static final int HASH_SIZE = 32;

    private final RocksDbDatabase database;

    public RocksDbBlockStore(
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
            Block block
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        database.put(
                key(block.header().hash()),
                BlockSerializer.serialize(block)
        );
    }

    public void save(
            RocksDbWriteBatch batch,
            Block block
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        batch.put(
                key(block.header().hash()),
                BlockSerializer.serialize(block)
        );
    }

    @Override
    public Optional<Block> find(
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

        Block block =
                BlockParser.parse(value);

        Hash256 actualHash =
                block.header().hash();

        if (!actualHash.equals(blockHash)) {
            throw new IllegalStateException(
                    "Stored block hash mismatch"
            );
        }

        return Optional.of(
                block
        );
    }

    public long serializedSize(Hash256 blockHash) {
        if (blockHash == null) throw new IllegalArgumentException("blockHash must not be null");
        byte[] value = database.get(key(blockHash));
        return value == null ? 0L : value.length;
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

        database.delete(
                key(blockHash)
        );
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

        batch.delete(
                key(blockHash)
        );
    }

    private static byte[] key(
            Hash256 blockHash
    ) {
        byte[] hash =
                blockHash.bytes();

        if (hash.length != HASH_SIZE) {
            throw new IllegalStateException(
                    "Invalid block hash length: "
                            + hash.length
            );
        }

        byte[] key =
                new byte[
                        1 + HASH_SIZE
                        ];

        key[0] =
                BLOCK_PREFIX;

        System.arraycopy(
                hash,
                0,
                key,
                1,
                HASH_SIZE
        );

        return key;
    }
}