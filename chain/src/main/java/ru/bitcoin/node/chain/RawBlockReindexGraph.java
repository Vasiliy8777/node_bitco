package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Ephemeral, disk-backed topological view of raw block bodies used by full reindex.
 * It deliberately avoids retaining the complete block graph in the Java heap.
 */
final class RawBlockReindexGraph {
    private static final int HASH_SIZE = 32;
    private static final int BUILD_BATCH = 4096;

    private final RocksDbDatabase database;
    private long discovered;

    RawBlockReindexGraph(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    long build(RocksDbBlockStore blocks, Hash256 genesisHash) {
        clear();
        class Builder implements AutoCloseable {
            RocksDbWriteBatch batch = new RocksDbWriteBatch();
            int pending;
            long count;
            void accept(ru.bitcoin.node.protocol.block.BlockHeader header) {
                Hash256 hash = header.hash();
                batch.put(memberKey(hash), header.previousBlockHash().bytes());
                if (!hash.equals(genesisHash)) batch.put(edgeKey(header.previousBlockHash(), hash), hash.bytes());
                count++;
                if (++pending >= BUILD_BATCH) flush();
            }
            void flush() {
                if (pending == 0) return;
                database.write(batch);
                batch.close();
                batch = new RocksDbWriteBatch();
                pending = 0;
            }
            public void close() { batch.close(); }
        }
        try (var builder = new Builder()) {
            blocks.forEachHeader(builder::accept);
            builder.flush();
            discovered = builder.count;
        }
        if (discovered == 0) throw new IllegalStateException("Cannot reindex: no persisted block bodies");
        if (!contains(genesisHash)) throw new IllegalStateException("Cannot reindex: genesis header is missing from block-body namespace");

        long head = 0;
        long tail = 1;
        putInitial(genesisHash);
        while (head < tail) {
            long batchEnd = Math.min(tail, head + BUILD_BATCH);
            try (var batch = new RocksDbWriteBatch()) {
                while (head < batchEnd) {
                    Hash256 parent = hashAt(head++);
                    long parentHeight = height(parent);
                    long[] nextTail = {tail};
                    database.forEachEntryByKeyPrefix(edgePrefix(parent), (key, value) -> {
                        Hash256 child = new Hash256(value);
                        if (database.get(memberKey(child)) == null) {
                            throw new IllegalStateException("Cannot reindex: raw edge references missing child " + child.toDisplayHex());
                        }
                        // Every block hash has exactly one previous-block hash. Seeing it twice means
                        // corrupted temporary topology rather than a legitimate DAG merge.
                        if (database.get(heightKey(child)) != null) {
                            throw new IllegalStateException("Cannot reindex: duplicate/cyclic raw block topology at " + child.toDisplayHex());
                        }
                        batch.put(heightKey(child), longBytes(Math.addExact(parentHeight, 1L)));
                        batch.put(queueKey(nextTail[0]++), child.bytes());
                    });
                    tail = nextTail[0];
                }
                database.write(batch);
            }
        }
        if (tail != discovered) {
            throw new IllegalStateException("Cannot reindex: raw block graph contains an orphan, cycle, or chain not rooted at selected genesis");
        }
        return discovered;
    }

    boolean contains(Hash256 hash) { return database.get(memberKey(hash)) != null; }

    long height(Hash256 hash) {
        byte[] value = database.get(heightKey(hash));
        if (value == null || value.length != Long.BYTES) {
            throw new IllegalStateException("Raw reindex height is missing for " + hash.toDisplayHex());
        }
        return ByteBuffer.wrap(value).getLong();
    }

    Hash256 hashAt(long sequence) {
        byte[] value = database.get(queueKey(sequence));
        if (value == null || value.length != HASH_SIZE) {
            throw new IllegalStateException("Raw reindex queue entry is missing at " + sequence);
        }
        return new Hash256(value);
    }

    boolean isFailedBranch(Hash256 hash) { return database.get(failedKey(hash)) != null; }

    void markFailedBranch(Hash256 hash) { database.put(failedKey(hash), new byte[]{1}); }

    long discovered() { return discovered; }

    void clear() {
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(RocksDbNamespaces.FULL_REINDEX_RAW_MEMBERSHIP);
            batch.deletePrefix(RocksDbNamespaces.FULL_REINDEX_RAW_EDGE);
            batch.deletePrefix(RocksDbNamespaces.FULL_REINDEX_RAW_QUEUE);
            batch.deletePrefix(RocksDbNamespaces.FULL_REINDEX_RAW_HEIGHT);
            batch.deletePrefix(RocksDbNamespaces.FULL_REINDEX_FAILED_BRANCH);
            database.write(batch);
        }
        discovered = 0;
    }

    private void putInitial(Hash256 genesisHash) {
        try (var batch = new RocksDbWriteBatch()) {
            batch.put(heightKey(genesisHash), longBytes(0));
            batch.put(queueKey(0), genesisHash.bytes());
            database.write(batch);
        }
    }

    private static byte[] memberKey(Hash256 hash) { return hashKey(RocksDbNamespaces.FULL_REINDEX_RAW_MEMBERSHIP, hash); }
    private static byte[] heightKey(Hash256 hash) { return hashKey(RocksDbNamespaces.FULL_REINDEX_RAW_HEIGHT, hash); }
    private static byte[] failedKey(Hash256 hash) { return hashKey(RocksDbNamespaces.FULL_REINDEX_FAILED_BRANCH, hash); }

    private static byte[] hashKey(byte prefix, Hash256 hash) {
        byte[] key = new byte[1 + HASH_SIZE];
        key[0] = prefix;
        System.arraycopy(hash.bytes(), 0, key, 1, HASH_SIZE);
        return key;
    }

    private static byte[] edgePrefix(Hash256 parent) {
        byte[] key = new byte[1 + HASH_SIZE];
        key[0] = RocksDbNamespaces.FULL_REINDEX_RAW_EDGE;
        System.arraycopy(parent.bytes(), 0, key, 1, HASH_SIZE);
        return key;
    }

    private static byte[] edgeKey(Hash256 parent, Hash256 child) {
        byte[] key = new byte[1 + HASH_SIZE + HASH_SIZE];
        key[0] = RocksDbNamespaces.FULL_REINDEX_RAW_EDGE;
        System.arraycopy(parent.bytes(), 0, key, 1, HASH_SIZE);
        System.arraycopy(child.bytes(), 0, key, 1 + HASH_SIZE, HASH_SIZE);
        return key;
    }

    private static byte[] queueKey(long sequence) {
        byte[] key = new byte[1 + Long.BYTES];
        key[0] = RocksDbNamespaces.FULL_REINDEX_RAW_QUEUE;
        ByteBuffer.wrap(key, 1, Long.BYTES).putLong(sequence);
        return key;
    }

    private static byte[] longBytes(long value) { return ByteBuffer.allocate(Long.BYTES).putLong(value).array(); }
}
