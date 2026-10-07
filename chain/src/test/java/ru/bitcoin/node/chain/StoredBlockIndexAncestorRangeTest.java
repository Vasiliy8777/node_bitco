package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StoredBlockIndexAncestorRangeTest {
    @TempDir Path directory;

    @Test
    void verifiedRangeAvoidsAllRepeatedIndexAndSkipReadsWithoutMixingEqualHeightForks() {
        var main = path(512);
        var fork = new ArrayList<BlockIndex>(main.subList(0, 65));
        for (int height = 65; height <= 512; height++) fork.add(child(fork.getLast(), height + 10000));
        try (var database = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockIndexStore(database);
            var all = new ArrayList<>(main);
            all.addAll(fork.subList(65, fork.size()));
            new ru.bitcoin.node.chain.storage.KnownHeaderStorage(database, store,
                    new ru.bitcoin.node.storage.chain.RocksDbChainStateStore(database))
                    .saveBatch(all, main.getLast());
            var lookup = new StoredBlockIndexLookup(store);
            lookup.prefetchHeightRange(100, 200, () -> {});
            assertEquals(main.subList(100, 201), lookup.ancestorRange(main.getLast(), 100, 200, () -> {}));
            long before = database.ioStats().gets();
            for (int height = 100; height <= 200; height++) {
                assertEquals(main.get(height).hash(), lookup.ancestor(main.getLast(), height).hash());
            }
            assertEquals(0, database.ioStats().gets() - before,
                    "Peer availability must reuse the verified window without even skip-record reads");
            assertEquals(fork.get(150).hash(), lookup.ancestor(fork.getLast(), 150).hash());
            assertNotEquals(main.get(150).hash(), fork.get(150).hash());
            lookup.ancestorRange(fork.getLast(), 100, 200, () -> {});
            assertEquals(main.get(150).hash(), lookup.ancestor(main.getLast(), 150).hash());
            assertEquals(fork.get(150).hash(), lookup.ancestor(fork.getLast(), 150).hash());
            var newerMain = child(main.getLast(), 20000);
            var newerFork = child(fork.getLast(), 20001);
            store.save(BlockIndexStorageMapper.toStored(newerMain));
            store.save(BlockIndexStorageMapper.toStored(newerFork));
            lookup.find(main.getLast().hash());
            lookup.find(fork.getLast().hash());
            before = database.ioStats().gets();
            assertEquals(main.get(150).hash(), lookup.ancestor(newerMain, 150).hash());
            assertEquals(fork.get(150).hash(), lookup.ancestor(newerFork, 150).hash());
            assertEquals(main.get(150).hash(), lookup.ancestor(main.get(511), 150).hash());
            assertEquals(fork.get(150).hash(), lookup.ancestor(fork.get(511), 150).hash());
            assertEquals(0, database.ioStats().gets() - before,
                    "New peer tips must bridge to the exact cached root without rewalking the historical range");
        }
    }

    @Test
    void cancellationAndMissingParentsNeverPublishAPartiallyVerifiedRange() {
        var indexes = path(200);
        var values = new HashMap<Hash256, StoredBlockIndex>();
        BlockIndexStore store = memory(values);
        indexes.forEach(index -> store.save(BlockIndexStorageMapper.toStored(index)));
        var lookup = new StoredBlockIndexLookup(store);
        var checks = new AtomicInteger();
        var cancelled = new java.util.concurrent.CancellationException("cancel range");
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class,
                () -> lookup.ancestorRange(indexes.getLast(), 90, 150, () -> {
                    if (checks.incrementAndGet() == 60) throw cancelled;
                })));
        assertUncachedAncestry(lookup, indexes.getLast(), 140);
        store.delete(indexes.get(100).hash());
        // Use a fresh lookup so existing positive records cannot hide the missing parent.
        var missingLookup = new StoredBlockIndexLookup(store);
        assertThrows(IllegalStateException.class,
                () -> missingLookup.ancestorRange(indexes.getLast(), 90, 150, () -> {}));
        assertUncachedAncestry(missingLookup, indexes.getLast(), 140);
        store.save(BlockIndexStorageMapper.toStored(indexes.get(100)));
        assertEquals(indexes.subList(90, 151), missingLookup.ancestorRange(indexes.getLast(), 90, 150, () -> {}));
        checks.set(0);
        assertEquals(indexes.get(140).hash(), missingLookup.ancestor(indexes.getLast(), 140, checks::incrementAndGet).hash());
        assertEquals(1, checks.get(), "Only the cancellation checkpoint is needed for a verified cache hit");
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class,
                () -> missingLookup.ancestor(indexes.getLast(), 140, () -> { throw cancelled; })));
    }

    @Test
    void smallCacheEvictionAndDisabledCachingRetainCorrectAncestry() {
        var indexes = path(20);
        var store = memory(new HashMap<>());
        indexes.forEach(index -> store.save(BlockIndexStorageMapper.toStored(index)));
        for (int capacity : new int[]{0, 2}) {
            var lookup = new StoredBlockIndexLookup(store, capacity);
            assertEquals(indexes.subList(5, 16), lookup.ancestorRange(indexes.getLast(), 5, 15, () -> {}));
            assertUncachedAncestry(lookup, indexes.getLast(), 5);
            assertEquals(indexes.get(5).hash(), lookup.ancestor(indexes.getLast(), 5).hash());
        }
    }

    private static void assertUncachedAncestry(StoredBlockIndexLookup lookup, BlockIndex root, long height) {
        var checks = new AtomicInteger();
        assertEquals(height, lookup.ancestor(root, height, checks::incrementAndGet).height());
        assertTrue(checks.get() > 1, "No partial/evicted range should bypass ancestry traversal");
    }

    private static BlockIndexStore memory(Map<Hash256, StoredBlockIndex> values) {
        return new BlockIndexStore() {
            public void save(StoredBlockIndex index) { values.put(index.hash(), index); }
            public Optional<StoredBlockIndex> find(Hash256 hash) { return Optional.ofNullable(values.get(hash)); }
            public void delete(Hash256 hash) { values.remove(hash); }
        };
    }

    private static List<BlockIndex> path(int count) {
        var indexes = new ArrayList<BlockIndex>();
        indexes.add(BlockIndexFactory.createGenesis(GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header()));
        for (int height = 1; height <= count; height++) indexes.add(child(indexes.getLast(), height));
        return indexes;
    }

    private static BlockIndex child(BlockIndex parent, long nonce) {
        return BlockIndexFactory.createChild(parent, new BlockHeader(4, parent.hash(), parent.hash(),
                new UInt32(parent.header().timestamp().value() + 1), parent.header().bits(), new UInt32(nonce)));
    }
}
