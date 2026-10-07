package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;

import java.util.List;
import java.util.Optional;

public final class RocksDbBlockIndexStore
        implements BlockIndexStore {
    private static final int HASH_SIZE = 32;
    private static final byte BLOCK_INDEX_PREFIX = RocksDbNamespaces.BLOCK_INDEX;
    private static final byte WORK_INDEX_PREFIX = RocksDbNamespaces.BLOCK_WORK_INDEX;
    private static final byte[] WORK_INDEX_VERSION_KEY =
            RocksDbNamespaces.singletonKey(RocksDbNamespaces.BLOCK_WORK_INDEX_VERSION);
    private static final byte HEIGHT_INDEX_PREFIX = RocksDbNamespaces.BLOCK_HEIGHT_INDEX;
    private static final byte[] HEIGHT_INDEX_VERSION_KEY =
            RocksDbNamespaces.singletonKey(RocksDbNamespaces.BLOCK_HEIGHT_INDEX_VERSION);
    private static final byte SKIP_INDEX_PREFIX = RocksDbNamespaces.BLOCK_SKIP_INDEX;
    private static final byte[] SKIP_INDEX_VERSION_KEY =
            RocksDbNamespaces.singletonKey(RocksDbNamespaces.BLOCK_SKIP_INDEX_VERSION);

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

    /** Bounded cache hints only: height entries do not establish branch membership. */
    public List<StoredBlockIndex> readHeightHints(long firstHeight, long lastHeight,
            int limit, java.util.function.Predicate<Hash256> needed, Runnable checkpoint) {
        if (firstHeight < 0 || lastHeight < firstHeight || limit < 1 || limit > 2048) {
            throw new IllegalArgumentException("Invalid height hint range or limit");
        }
        java.util.Objects.requireNonNull(needed, "needed");
        java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.run();
        // Never trigger a whole-index migration from the download hot path.
        byte[] version = database.get(HEIGHT_INDEX_VERSION_KEY);
        if (!java.util.Arrays.equals(version, new byte[]{1})) return List.of();
        byte[] cursor = new byte[9];
        cursor[0] = HEIGHT_INDEX_PREFIX;
        java.nio.ByteBuffer.wrap(cursor, 1, 8).putLong(firstHeight);
        var hashes = new java.util.ArrayList<Hash256>();
        var heights = new java.util.ArrayList<Long>();
        int[] visited = {0};
        database.visitPrefixAscendingAfter(HEIGHT_INDEX_PREFIX, cursor, (key, value) -> {
            checkpoint.run();
            if (++visited[0] > limit) return false;
            if (key.length != 41 || value.length != HASH_SIZE) return true;
            long height = java.nio.ByteBuffer.wrap(key, 1, 8).getLong();
            if (height > lastHeight) return false;
            Hash256 hash = new Hash256(value);
            if (height >= firstHeight && needed.test(hash)) {
                hashes.add(hash);
                heights.add(height);
            }
            return true;
        });
        var result = new java.util.ArrayList<StoredBlockIndex>();
        // Keep cancellation and the native database monitor responsive.
        for (int offset = 0; offset < hashes.size(); offset += 256) {
            checkpoint.run();
            int end = Math.min(offset + 256, hashes.size());
            var keys = hashes.subList(offset, end).stream().map(RocksDbBlockIndexStore::key).toList();
            var values = database.getAll(keys);
            for (int i = offset; i < end; i++) {
                byte[] value = values.get(i - offset);
                if (value == null) continue;
                StoredBlockIndex index = StoredBlockIndexSerializer.deserialize(value);
                if (!index.hash().equals(hashes.get(i))) {
                    throw new IllegalStateException("Stored block index hash mismatch in height hints");
                }
                if (index.height() == heights.get(i)) result.add(index);
            }
        }
        checkpoint.run();
        return List.copyOf(result);
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

    /**
     * Streams primary block-index records in ascending height order without materializing
     * the complete index. Returning false from the visitor stops the scan immediately.
     */
    public void visitByHeightAscending(java.util.function.Predicate<StoredBlockIndex> visitor) {
        java.util.Objects.requireNonNull(visitor, "visitor");
        ensureHeightIndex();
        database.visitPrefixAscending(HEIGHT_INDEX_PREFIX, (key, value) -> {
            if (key.length != 1 + 8 + HASH_SIZE || value.length != HASH_SIZE) return true;
            long indexedHeight = java.nio.ByteBuffer.wrap(key, 1, 8).getLong();
            Hash256 hash = new Hash256(value);
            StoredBlockIndex current = find(hash).orElse(null);
            if (current == null || current.height() != indexedHeight
                    || !java.util.Arrays.equals(key, heightKey(current))) {
                return true;
            }
            return visitor.test(current);
        });
    }

    private void ensureHeightIndex() {
        synchronized (database) {
            byte[] version = database.get(HEIGHT_INDEX_VERSION_KEY);
            if (version != null) {
                if (!java.util.Arrays.equals(version, new byte[]{1})) {
                    throw new IllegalStateException("Unsupported block height index version");
                }
                return;
            }
            class Migration implements AutoCloseable {
                RocksDbWriteBatch batch = new RocksDbWriteBatch();
                int count;
                void add(StoredBlockIndex index) {
                    batch.put(heightKey(index), index.hash().bytes());
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
                migration.batch.put(HEIGHT_INDEX_VERSION_KEY, new byte[]{1});
                database.write(migration.batch);
            }
        }
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

    private static byte[] heightKey(StoredBlockIndex index) {
        byte[] key = new byte[1 + 8 + HASH_SIZE];
        key[0] = HEIGHT_INDEX_PREFIX;
        java.nio.ByteBuffer.wrap(key, 1, 8).putLong(index.height());
        System.arraycopy(index.hash().bytes(), 0, key, 9, HASH_SIZE);
        return key;
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


    public static long getSkipHeight(long height) {
        if (height < 2) return 0;
        if ((height & 1L) != 0) return invertLowestOne(invertLowestOne(height - 1)) + 1;
        return invertLowestOne(height);
    }

    private static long invertLowestOne(long value) {
        return value & (value - 1);
    }

    public Optional<Hash256> findSkipHash(Hash256 hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        byte[] value = database.get(skipKey(hash));
        if (value == null) return Optional.empty();
        if (value.length != HASH_SIZE) throw new IllegalStateException("Invalid block skip hash size: " + value.length);
        return Optional.of(new Hash256(value));
    }

    /** Builds the branch-safe skip namespace once for existing v1 block-index records. */
    public void ensureSkipIndex() {
        synchronized (database) {
            byte[] version = database.get(SKIP_INDEX_VERSION_KEY);
            if (version != null) {
                if (!java.util.Arrays.equals(version, new byte[]{1})) {
                    throw new IllegalStateException("Unsupported block skip index version");
                }
                return;
            }
            ensureHeightIndex();
            final java.util.Map<Hash256, Hash256> pending = new java.util.HashMap<>(8192);
            class Migration implements AutoCloseable {
                RocksDbWriteBatch batch = new RocksDbWriteBatch();
                int count;
                Hash256 skipOf(Hash256 hash) {
                    Hash256 local = pending.get(hash);
                    return local != null ? local : findSkipHash(hash).orElse(null);
                }
                StoredBlockIndex ancestor(StoredBlockIndex start, long targetHeight) {
                    StoredBlockIndex current = start;
                    while (current.height() > targetHeight) {
                        long skipHeight = getSkipHeight(current.height());
                        long skipPrev = getSkipHeight(current.height() - 1);
                        Hash256 skip = skipOf(current.hash());
                        boolean useSkip = skip != null && (skipHeight == targetHeight
                                || (skipHeight > targetHeight && !(skipPrev < skipHeight - 2 && skipPrev >= targetHeight)));
                        Hash256 nextHash = useSkip ? skip : current.previousBlockHash();
                        current = find(nextHash).orElseThrow(() -> new IllegalStateException(
                                "Missing block-index ancestor while building skip index: " + nextHash.toDisplayHex()));
                    }
                    return current;
                }
                void add(StoredBlockIndex index) {
                    if (index.height() == 0) return;
                    long target = getSkipHeight(index.height());
                    StoredBlockIndex ancestor = ancestor(index, target);
                    batch.put(skipKey(index.hash()), ancestor.hash().bytes());
                    pending.put(index.hash(), ancestor.hash());
                    if (++count == 4096) flush();
                }
                void flush() {
                    if (count == 0) return;
                    database.write(batch, false);
                    batch.close();
                    batch = new RocksDbWriteBatch();
                    pending.clear();
                    count = 0;
                }
                public void close() { batch.close(); }
            }
            try (var migration = new Migration()) {
                visitByHeightAscending(index -> { migration.add(index); return true; });
                migration.flush();
                try (var marker = new RocksDbWriteBatch()) {
                    marker.put(SKIP_INDEX_VERSION_KEY, new byte[]{1});
                    database.write(marker, true);
                }
            }
        }
    }

    public void saveSkipNew(RocksDbWriteBatch batch, StoredBlockIndex index, Hash256 skipHash) {
        if (batch == null || index == null || skipHash == null) throw new IllegalArgumentException("skip arguments must not be null");
        if (index.height() > 0) batch.put(skipKey(index.hash()), skipHash.bytes());
    }

    private static byte[] skipKey(Hash256 hash) {
        byte[] key = new byte[1 + HASH_SIZE];
        key[0] = SKIP_INDEX_PREFIX;
        System.arraycopy(hash.bytes(), 0, key, 1, HASH_SIZE);
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

        find(blockIndex.hash()).ifPresent(previous -> {
            batch.delete(workKey(previous));
            batch.delete(heightKey(previous));
        });
        batch.put(workKey(blockIndex), blockIndex.hash().bytes());
        batch.put(heightKey(blockIndex), blockIndex.hash().bytes());
        batch.put(
                key(blockIndex.hash()),
                StoredBlockIndexSerializer.serialize(
                        blockIndex
                )
        );
    }

    /**
     * Adds a block-index record that the caller has already established is new.
     * This avoids a redundant RocksDB read on the hot header-IBD path.
     */
    public void saveNew(
            RocksDbWriteBatch batch,
            StoredBlockIndex blockIndex
    ) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (blockIndex == null) {
            throw new IllegalArgumentException("blockIndex must not be null");
        }
        batch.put(workKey(blockIndex), blockIndex.hash().bytes());
        batch.put(heightKey(blockIndex), blockIndex.hash().bytes());
        batch.put(
                key(blockIndex.hash()),
                StoredBlockIndexSerializer.serialize(blockIndex)
        );
    }

    /** Clears primary and secondary block-index namespaces atomically with the caller's batch. */
    public void clear(RocksDbWriteBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.deletePrefix(BLOCK_INDEX_PREFIX);
        batch.deletePrefix(WORK_INDEX_PREFIX);
        batch.delete(WORK_INDEX_VERSION_KEY);
        batch.deletePrefix(HEIGHT_INDEX_PREFIX);
        batch.delete(HEIGHT_INDEX_VERSION_KEY);
        batch.deletePrefix(SKIP_INDEX_PREFIX);
        batch.delete(SKIP_INDEX_VERSION_KEY);
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

        find(hash).ifPresent(previous -> {
            batch.delete(workKey(previous));
            batch.delete(heightKey(previous));
        });
        batch.delete(
                key(hash)
        );
        batch.delete(skipKey(hash));
    }
}
