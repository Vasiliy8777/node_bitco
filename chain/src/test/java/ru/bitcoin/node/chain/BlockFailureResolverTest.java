package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BlockFailureResolverTest {
    @TempDir Path directory;

    @Test
    void validAnchorCertifiesOnlyItsAncestorsAndInvalidatesOnCommittedFailure() {
        var indexes = chain(8);
        var reads = new AtomicInteger();
        var fork = new BlockIndex(hash(99), index(3).header(), 3,
                index(2).hash(), index(3).chainWork());
        indexes.put(fork.hash(), fork);
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            public BlockIndex find(Hash256 hash) { reads.incrementAndGet(); return indexes.get(hash); }
            public BlockIndex ancestor(BlockIndex tip, long height) { return index((int) height); }
        };
        try (var db = new RocksDbDatabase(directory)) {
            var failures = new RocksDbBlockFailureStore(db);
            failures.markFailed(fork.hash());
            var resolver = new BlockFailureResolver(lookup, failures);
            resolver.seedKnownValid(index(7));
            assertFalse(resolver.isFailed(index(3)));
            assertEquals(0, reads.get(), "Valid ancestry should not require a historical parent walk");
            assertTrue(resolver.isFailed(fork), "Same height on another branch is not certified");
            failures.markFailed(index(2).hash());
            assertTrue(resolver.isFailed(index(3)), "Committed invalidation must revoke the anchor");
            assertTrue(reads.get() > 0);
        }
    }

    @Test
    void interruptedFailureWalkStopsBeforeReadingHistory() {
        try (var db = new RocksDbDatabase(directory)) {
            var reads = new AtomicInteger();
            var resolver = new BlockFailureResolver(hash -> {
                reads.incrementAndGet();
                return index(0);
            }, new RocksDbBlockFailureStore(db));
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.util.concurrent.CancellationException.class, () -> resolver.isFailed(index(3)));
                assertEquals(0, reads.get());
            } finally { Thread.interrupted(); }
        }
    }

    @Test
    void sequentialHistoryNeedsOnlyOneParentReadPerNewBlockEvenAfterCacheEviction() {
        try (var db = new RocksDbDatabase(directory)) {
            var failures = new RocksDbBlockFailureStore(db);
            var indexes = new HashMap<Hash256, BlockIndex>();
            var reads = new AtomicInteger();
            var resolver = new BlockFailureResolver(hash -> {
                reads.incrementAndGet();
                return indexes.get(hash);
            }, failures);
            for (int i = 0; i < 70_000; i++) {
                BlockIndex index = index(i);
                indexes.put(index.hash(), index);
                // Normal header writes must not invalidate cached failure ancestry.
                if (i % 1000 == 0) db.put(new byte[]{1}, new byte[]{1});
                assertFalse(resolver.isFailed(index));
            }
            assertEquals(69_999, reads.get());
            assertFalse(resolver.isFailed(index(69_999)));
            assertEquals(69_999, reads.get());
        }
    }

    @Test
    void seededKnownValidTipAvoidsHistoricalWalkAndStillInvalidatesOnFailureRevision() {
        var indexes = chain(4);
        try (var db = new RocksDbDatabase(directory)) {
            var failures = new RocksDbBlockFailureStore(db);
            var reads = new AtomicInteger();
            var resolver = new BlockFailureResolver(hash -> {
                reads.incrementAndGet();
                return indexes.get(hash);
            }, failures);

            resolver.seedKnownValid(index(2));
            assertFalse(resolver.isFailed(index(3)));
            assertEquals(1, reads.get());

            failures.markFailed(index(1).hash());
            assertTrue(resolver.isFailed(index(3)));
            assertTrue(reads.get() > 1);
        }
    }

    @Test
    void invalidatesAcrossStoreInstancesOnlyAfterBatchCommitAndSurvivesRestart() {
        var indexes = chain(4);
        try (var db = new RocksDbDatabase(directory)) {
            var reader = new RocksDbBlockFailureStore(db);
            var writer = new RocksDbBlockFailureStore(db);
            var resolver = new BlockFailureResolver(indexes::get, reader);
            assertFalse(resolver.isFailed(index(3)));
            try (var abandoned = new RocksDbWriteBatch()) {
                writer.markFailed(abandoned, index(1).hash());
            }
            assertFalse(resolver.isFailed(index(3)));
            try (var batch = new RocksDbWriteBatch()) {
                writer.markFailed(batch, index(1).hash());
                assertFalse(resolver.isFailed(index(3)));
                db.write(batch);
            }
            assertTrue(resolver.isFailed(index(3)));
            assertFalse(resolver.isFailed(index(0)));
        }
        try (var db = new RocksDbDatabase(directory)) {
            assertTrue(new BlockFailureResolver(indexes::get, new RocksDbBlockFailureStore(db))
                    .isFailed(index(3)));
        }
    }

    @Test
    void hypotheticalFailureCannotPolluteCommittedOrDifferentHypotheticalResults() {
        var indexes = chain(4);
        try (var db = new RocksDbDatabase(directory)) {
            var failures = new RocksDbBlockFailureStore(db);
            var resolver = new BlockFailureResolver(indexes::get, failures);
            assertFalse(resolver.isFailed(index(3)));
            assertTrue(resolver.isFailed(index(3), index(1).hash()));
            assertFalse(resolver.isFailed(index(3), hash(99)));
            assertFalse(resolver.isFailed(index(3)));
            failures.markFailed(index(2).hash());
            assertTrue(resolver.isFailed(index(3)));
            assertFalse(resolver.isFailed(index(1)));
        }
    }

    @Test
    void namespaceGenerationTracksCommittedChangesIncludingReplayedBatches() {
        try (var db = new RocksDbDatabase(directory);
             var batch = new RocksDbWriteBatch()) {
            byte[] failureKey = {7, 1};
            long original = db.namespaceVersion((byte) 7);
            batch.put(failureKey, new byte[]{1});
            assertEquals(original, db.namespaceVersion((byte) 7));
            db.write(batch);
            assertEquals(original + 1, db.namespaceVersion((byte) 7));
            db.put(new byte[]{1, 1}, new byte[]{1});
            assertEquals(original + 1, db.namespaceVersion((byte) 7));
            db.write(batch);
            assertEquals(original + 2, db.namespaceVersion((byte) 7));
            db.delete(failureKey);
            assertEquals(original + 3, db.namespaceVersion((byte) 7));
        }
    }

    @Test
    void retriesWhenFailureIsCommittedDuringAncestryLookup() {
        var indexes = chain(4);
        try (var db = new RocksDbDatabase(directory)) {
            var failures = new RocksDbBlockFailureStore(db);
            var committed = new java.util.concurrent.atomic.AtomicBoolean();
            var resolver = new BlockFailureResolver(hash -> {
                if (committed.compareAndSet(false, true)) failures.markFailed(index(3).hash());
                return indexes.get(hash);
            }, failures);
            assertTrue(resolver.isFailed(index(3)));
            assertTrue(resolver.isFailed(index(3)));
        }
    }

    @Test
    void missingAncestorDoesNotCacheUnverifiedPath() {
        var indexes = chain(4);
        indexes.remove(index(1).hash());
        try (var db = new RocksDbDatabase(directory)) {
            var resolver = new BlockFailureResolver(indexes::get, new RocksDbBlockFailureStore(db));
            assertThrows(IllegalStateException.class, () -> resolver.isFailed(index(3)));
            indexes.put(index(1).hash(), index(1));
            assertFalse(resolver.isFailed(index(3)));
        }
    }

    private static Map<Hash256, BlockIndex> chain(int length) {
        var indexes = new HashMap<Hash256, BlockIndex>();
        for (int i = 0; i < length; i++) indexes.put(index(i).hash(), index(i));
        return indexes;
    }

    private static final ru.bitcoin.node.protocol.block.BlockHeader HEADER =
            GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header();

    private static BlockIndex index(int height) {
        return new BlockIndex(hash(height), HEADER, height, hash(height - 1), BigInteger.valueOf(height + 1));
    }

    private static Hash256 hash(int value) {
        return new Hash256(ByteBuffer.allocate(32).putInt(value).array());
    }
}
