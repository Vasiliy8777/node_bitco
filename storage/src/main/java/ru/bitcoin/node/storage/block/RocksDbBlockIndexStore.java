package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.List;
import java.util.Optional;

public final class RocksDbBlockIndexStore
        implements BlockIndexStore {
    private static final int HASH_SIZE = 32;
    private static final byte BLOCK_INDEX_PREFIX = 0x01;
    private static final byte WORK_INDEX_PREFIX = 0x08;
    private static final byte[] WORK_INDEX_VERSION_KEY = {0x09};

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

        try (var batch = new RocksDbWriteBatch()) {
            save(batch, blockIndex);
            database.write(batch);
        }
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

    public List<StoredBlockIndex> findAll() {
        return database.valuesByPrefix(BLOCK_INDEX_PREFIX)
                .stream()
                .map(StoredBlockIndexSerializer::deserialize)
                .toList();
    }

    public void forEach(java.util.function.Consumer<StoredBlockIndex> visitor) {
        java.util.Objects.requireNonNull(visitor, "visitor");
        database.forEachValueByPrefix(BLOCK_INDEX_PREFIX,
                value -> visitor.accept(StoredBlockIndexSerializer.deserialize(value)));
    }

    /** Highest chainwork, then height, then display hash; stops at the first eligible entry. */
    public Optional<StoredBlockIndex> findBest(java.util.function.Predicate<StoredBlockIndex> eligible) {
        java.util.Objects.requireNonNull(eligible, "eligible");
        ensureWorkIndex();
        StoredBlockIndex[] result = new StoredBlockIndex[1];
        database.visitPrefixDescending(WORK_INDEX_PREFIX, (key, value) -> {
            StoredBlockIndex current = find(new Hash256(value)).orElse(null);
            // A batch may replace/delete the same primary record several times.
            // Old secondary entries must never resurrect a deleted or superseded candidate.
            if (current != null && java.util.Arrays.equals(key, workKey(current)) && eligible.test(current)) {
                result[0] = current;
                return false;
            }
            return true;
        });
        return Optional.ofNullable(result[0]);
    }

    private void ensureWorkIndex() {
        synchronized (database) {
            byte[] version = database.get(WORK_INDEX_VERSION_KEY);
            if (version != null) {
                if (!java.util.Arrays.equals(version, new byte[]{1})) {
                    throw new IllegalStateException("Unsupported block work index version");
                }
                return;
            }
            // Bounded, restartable migration. A missing marker causes replay after interruption.
            // The database monitor excludes commits while taking and indexing the primary view.
            class Migration implements AutoCloseable {
                RocksDbWriteBatch batch = new RocksDbWriteBatch();
                int count;
                void add(StoredBlockIndex index) {
                    batch.put(workKey(index), index.hash().bytes());
                    if (++count == 1024) {
                        database.write(batch);
                        batch.close();
                        batch = new RocksDbWriteBatch();
                        count = 0;
                    }
                }
                public void close() { batch.close(); }
            }
            try (var migration = new Migration()) {
                forEach(migration::add);
                migration.batch.put(WORK_INDEX_VERSION_KEY, new byte[]{1});
                database.write(migration.batch);
            }
        }
    }

    private static byte[] workKey(StoredBlockIndex index) {
        byte[] key = new byte[1 + 32 + 8 + 32];
        key[0] = WORK_INDEX_PREFIX;
        byte[] work = index.chainWork().toByteArray();
        int length = Math.min(32, work.length);
        System.arraycopy(work, work.length - length, key, 33 - length, length);
        java.nio.ByteBuffer.wrap(key, 33, 8).putLong(index.height());
        byte[] hash = index.hash().bytes();
        for (int i = 0; i < 32; i++) key[41 + i] = hash[31 - i];
        return key;
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

        try (var batch = new RocksDbWriteBatch()) {
            delete(batch, hash);
            database.write(batch);
        }
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

        find(blockIndex.hash()).ifPresent(previous -> batch.delete(workKey(previous)));
        batch.put(workKey(blockIndex), blockIndex.hash().bytes());
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

        find(hash).ifPresent(previous -> batch.delete(workKey(previous)));
        batch.delete(
                key(hash)
        );
    }
}
