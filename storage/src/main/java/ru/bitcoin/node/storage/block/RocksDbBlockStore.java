package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;

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

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            save(batch, block);
            database.write(batch);
        }
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

        Hash256 hash = block.header().hash();
        byte[] serialized = BlockSerializer.serialize(block);
        batch.put(key(hash), serialized);
        new RocksDbBlockAvailabilityStore(database).markData(batch, hash);
        new RocksDbPruneUsageStore(database).setBlockSize(batch, hash, serialized.length);
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

    /** Visits persisted block headers without retaining full transaction bodies in Java memory. */
    public void forEachHeader(java.util.function.Consumer<ru.bitcoin.node.protocol.block.BlockHeader> visitor) {
        java.util.Objects.requireNonNull(visitor, "visitor");
        database.forEachValueByPrefix(BLOCK_PREFIX, value -> {
            if (value.length < ru.bitcoin.node.protocol.block.BlockHeader.SERIALIZED_SIZE) {
                throw new IllegalStateException("Stored block is shorter than an 80-byte header");
            }
            byte[] headerBytes = java.util.Arrays.copyOf(
                    value, ru.bitcoin.node.protocol.block.BlockHeader.SERIALIZED_SIZE);
            visitor.accept(ru.bitcoin.node.protocol.serialization.BlockHeaderParser.parse(headerBytes));
        });
    }

    public long serializedSize(Hash256 blockHash) {
        if (blockHash == null) throw new IllegalArgumentException("blockHash must not be null");
        return new RocksDbPruneUsageStore(database).blockSize(blockHash);
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
        new RocksDbBlockAvailabilityStore(database).clearData(batch, blockHash);
        new RocksDbPruneUsageStore(database).setBlockSize(batch, blockHash, 0L);
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