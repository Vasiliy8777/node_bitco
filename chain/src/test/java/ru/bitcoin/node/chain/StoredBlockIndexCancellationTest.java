package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StoredBlockIndexCancellationTest {
    @TempDir Path directory;

    @Test
    void directParentLookupDoesNotReadAnUnusedSkipRecord() {
        try (var database = new RocksDbDatabase(directory.resolve("near-ancestor"))) {
            var parent = BlockIndexFactory.createGenesis(
                    GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
            var store = new RocksDbBlockIndexStore(database);
            store.save(BlockIndexStorageMapper.toStored(parent));
            // At height 2 the skip target is 0, so resolving height 1 must use pprev.
            var one = BlockIndexFactory.createChild(parent, new BlockHeader(4, parent.hash(), parent.hash(),
                    new UInt32(parent.header().timestamp().value() + 1), parent.header().bits(), new UInt32(1)));
            var two = BlockIndexFactory.createChild(one, new BlockHeader(4, one.hash(), one.hash(),
                    new UInt32(one.header().timestamp().value() + 1), one.header().bits(), new UInt32(2)));
            store.save(BlockIndexStorageMapper.toStored(one));
            store.save(BlockIndexStorageMapper.toStored(two));
            long before = database.ioStats().gets();
            assertEquals(one.hash(), new StoredBlockIndexLookup(store).ancestor(two, 1).hash());
            assertEquals(1, database.ioStats().gets() - before,
                    "Only the parent index is needed; fetching pskip adds a cold read");
        }
    }

    @Test
    void heightHintsWarmBothForksWithoutChangingAncestryOrRereadingCachedRecords() {
        try (var database = new RocksDbDatabase(directory.resolve("height-hints"))) {
            var store = new RocksDbBlockIndexStore(database);
            var genesis = BlockIndexFactory.createGenesis(
                    GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
            var left = genesis;
            var right = genesis;
            var indexes = new ArrayList<BlockIndex>();
            indexes.add(genesis);
            for (int i = 1; i <= 40; i++) {
                left = child(left, i);
                right = child(right, i + 1000);
                indexes.add(left);
                indexes.add(right);
            }
            indexes.forEach(index -> store.save(BlockIndexStorageMapper.toStored(index)));
            store.visitByHeightAscending(index -> true); // Establish the versioned secondary index.
            var lookup = new StoredBlockIndexLookup(store);
            var namespaceBefore = database.namespaceIoStats();
            lookup.prefetchHeightRange(1, 40, () -> {});
            assertEquals(0, database.namespaceIoStats().minus(namespaceBefore)
                    .gets(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_SKIP_INDEX),
                    "Sequential height warming must not speculatively read every skip pointer");
            long nativeBefore = database.readCacheStats().nativeKeys();
            for (var index : indexes) {
                if (index.height() == 0) continue;
                for (byte prefix : new byte[]{
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK,
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.UNDO,
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_FAILURE,
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_AVAILABILITY}) {
                    byte[] metadataKey = new byte[33];
                    metadataKey[0] = prefix;
                    System.arraycopy(index.hash().bytes(), 0, metadataKey, 1, 32);
                    assertNull(database.get(metadataKey));
                }
            }
            assertEquals(nativeBefore, database.readCacheStats().nativeKeys(),
                    "Both branches' metadata misses must be warmed alongside their indexes");
            long before = database.ioStats().gets();
            for (var index : indexes) {
                if (index.height() > 0) assertEquals(index.hash(), lookup.find(index.hash()).hash());
            }
            assertEquals(before, database.ioStats().gets(), "Both branches must be cached");
            assertEquals(left.previousBlockHash(), lookup.ancestor(left, 39).hash());
            assertEquals(right.previousBlockHash(), lookup.ancestor(right, 39).hash());
            before = database.ioStats().gets();
            lookup.prefetchHeightRange(1, 40, () -> {});
            assertEquals(1, database.ioStats().gets() - before,
                    "Warm ranges only read the height-index version, not primary records");
            assertEquals(3, store.readHeightHints(1, 40, 3, hash -> true, () -> {}).size());
            var failure = new java.util.concurrent.CancellationException("cancel prefetch");
            var checks = new AtomicInteger();
            assertSame(failure, assertThrows(java.util.concurrent.CancellationException.class,
                    () -> lookup.prefetchHeightRange(1, 40, () -> {
                        if (checks.incrementAndGet() == 5) throw failure;
                    })));
            assertEquals(left.hash(), lookup.find(left.hash()).hash());
        }
    }

    @Test
    void missingHeightIndexDoesNotMigrateOrNegativeCacheHeaders() {
        try (var database = new RocksDbDatabase(directory.resolve("no-height-marker"))) {
            var store = new RocksDbBlockIndexStore(database);
            var lookup = new StoredBlockIndexLookup(store);
            var genesis = BlockIndexFactory.createGenesis(
                    GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
            var one = child(genesis, 1);
            lookup.prefetchHeightRange(1, 1, () -> {});
            assertNull(lookup.find(one.hash()));
            store.save(BlockIndexStorageMapper.toStored(one));
            assertEquals(one.hash(), lookup.find(one.hash()).hash());
            assertNull(database.get(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.singletonKey(
                    ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_HEIGHT_INDEX_VERSION)));
        }
    }

    private static BlockIndex child(BlockIndex parent, int nonce) {
        return BlockIndexFactory.createChild(parent, new BlockHeader(4, parent.hash(), parent.hash(),
                new UInt32(parent.header().timestamp().value() + 1), parent.header().bits(), new UInt32(nonce)));
    }

    @Test
    void cancellationInterruptsBothSkipAndLinearAncestorWalks() {
        var indexes = new ArrayList<BlockIndex>();
        var parent = BlockIndexFactory.createGenesis(
                GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
        indexes.add(parent);
        for (int i = 1; i < 2048; i++) {
            parent = BlockIndexFactory.createChild(parent, new BlockHeader(4, parent.hash(), parent.hash(),
                    new UInt32(parent.header().timestamp().value() + 1), parent.header().bits(), new UInt32(i)));
            indexes.add(parent);
        }
        var values = new HashMap<Hash256, StoredBlockIndex>();
        BlockIndexStore memory = new BlockIndexStore() {
            public void save(StoredBlockIndex value) { values.put(value.hash(), value); }
            public Optional<StoredBlockIndex> find(Hash256 hash) { return Optional.ofNullable(values.get(hash)); }
            public void delete(Hash256 hash) { values.remove(hash); }
        };
        indexes.forEach(index -> memory.save(BlockIndexStorageMapper.toStored(index)));
        assertCancellation(new StoredBlockIndexLookup(memory), indexes.getLast());
        try (var database = new RocksDbDatabase(directory.resolve("skip-cancellation"))) {
            var store = new RocksDbBlockIndexStore(database);
            new ru.bitcoin.node.chain.storage.KnownHeaderStorage(database, store,
                    new ru.bitcoin.node.storage.chain.RocksDbChainStateStore(database))
                    .saveBatch(indexes, indexes.getLast());
            assertCancellation(new StoredBlockIndexLookup(store), indexes.getLast());
        }
    }

    private static void assertCancellation(StoredBlockIndexLookup lookup, BlockIndex tip) {
        var checks = new AtomicInteger();
        var cancelled = new java.util.concurrent.CancellationException("stop ancestry walk");
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class,
                () -> lookup.ancestor(tip, 500, () -> {
                    if (checks.incrementAndGet() == 3) throw cancelled;
                })));
        assertEquals(3, checks.get());
        assertEquals(500, lookup.ancestor(tip, 500).height(), "Cancellation must not damage stored ancestry");
    }
}
