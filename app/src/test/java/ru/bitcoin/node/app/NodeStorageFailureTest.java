package ru.bitcoin.node.app;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rocksdb.*;
import org.springframework.test.util.ReflectionTestUtils;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoFinalizer;
import ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore;
import ru.bitcoin.node.storage.utxo.RocksDbSnapshotChainStateStore;
import ru.bitcoin.node.common.types.Hash256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Inject errors at the JNI write boundary, before commit; never fills the host disk. */
class NodeStorageFailureTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedSnapshotPromotionResumesAtEveryWriteBoundary(Status.SubCode code) throws Exception {
        // Marker/clear, first 10,000 coins, remaining coin, cleanup are separate commits.
        for (int failAt = 1; failAt <= 4; failAt++) {
            Path path = directory.resolve("snapshot-" + failAt);
            var base = Hash256.fromDisplayHex("01".repeat(32));
            var advanced = Hash256.fromDisplayHex("02".repeat(32));
            try (var db = new RocksDbDatabase(path)) {
                var tips = new RocksDbChainStateStore(db);
                tips.saveActiveTipHash(base);
                new RocksDbSnapshotChainStateStore(db).activate(base, 10, advanced, 20);
                var background = new RocksDbAssumeUtxoBackgroundStore(db);
                background.initialize(base, 10);
                background.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, advanced, 20);
                // Metadata test: finalizer copies opaque key/value pairs, without decoding coins.
                db.put(new byte[]{RocksDbNamespaces.UTXO, 99}, new byte[]{99});
                try (var batch = new RocksDbWriteBatch()) {
                    for (int i = 0; i <= 10_000; i++) batch.put(snapshotKey(i), new byte[]{(byte) i});
                    db.write(batch);
                }
                try (var fault = new WriteFailure(db, code, false, failAt)) {
                    var thrown = assertThrows(IllegalStateException.class,
                            () -> new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup());
                    assertSame(fault.error, thrown.getCause());
                    fault.assertUsed();
                    assertTrue(new RocksDbSnapshotChainStateStore(db).load().isPresent());
                    assertEquals(advanced, tips.loadActiveTipHash().orElseThrow());
                    for (int i = 0; i <= 10_000; i++) assertArrayEquals(new byte[]{(byte) i}, db.get(snapshotKey(i)));
                }
            }
            try (var db = new RocksDbDatabase(path)) {
                assertTrue(new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup());
                assertFalse(new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup());
                assertTrue(new RocksDbSnapshotChainStateStore(db).load().isEmpty());
                assertTrue(new RocksDbAssumeUtxoBackgroundStore(db).load().isEmpty());
                assertEquals(advanced, new RocksDbChainStateStore(db).loadActiveTipHash().orElseThrow());
                assertNull(db.get(new byte[]{RocksDbNamespaces.UTXO, 99}));
                for (int i = 0; i <= 10_000; i++) {
                    byte[] key = snapshotKey(i);
                    assertNull(db.get(key));
                    key[0] = RocksDbNamespaces.UTXO;
                    assertArrayEquals(new byte[]{(byte) i}, db.get(key));
                }
            }
        }
    }

    private static byte[] snapshotKey(int i) {
        return new byte[]{RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, (byte) (i >>> 8), (byte) i};
    }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedGenesisInitializationLeavesEmptyDatabase(Status.SubCode code) throws Exception {
        Path path = directory.resolve("initialization");
        try (var db = new RocksDbDatabase(path)) {
            var generations = generations(db);
            try (var fault = new WriteFailure(db, code)) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize());
                assertSame(fault.error, thrown.getCause());
                fault.assertUsed();
                assertTrue(db.isEmpty(), "Rejected genesis batch must leave no partial initialization");
                assertArrayEquals(generations, generations(db));
            }
        }
        try (var db = new RocksDbDatabase(path)) {
            assertTrue(db.isEmpty());
            new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize();
        }
        assertReopenedGenesisMatchesReference(path);
    }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedLegacyHeaderMigrationPreservesDatabase(Status.SubCode code) throws Exception {
        Path path = directory.resolve("legacy");
        List<String> before;
        try (var db = new RocksDbDatabase(path)) {
            new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize();
            db.delete(new byte[]{0x02, 0x02}); // Legacy database has no separate best-header tip.
            before = contents(db);
            var generations = generations(db);
            try (var fault = new WriteFailure(db, code, true)) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize());
                assertSame(fault.error, thrown.getCause());
                fault.assertUsed();
                assertContentsEqual(before, contents(db));
                assertArrayEquals(generations, generations(db));
            }
        }
        try (var db = new RocksDbDatabase(path)) {
            assertContentsEqual(before, contents(db));
            assertTrue(new RocksDbChainStateStore(db).loadBestHeaderTipHash().isEmpty());
            new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize();
        }
        assertReopenedGenesisMatchesReference(path);
    }

    private void assertReopenedGenesisMatchesReference(Path path) {
        try (var referenceDb = new RocksDbDatabase(directory.resolve("reference"));
             var reference = NodeProcessCrashTest.service(referenceDb);
             var db = new RocksDbDatabase(path);
             var actual = NodeProcessCrashTest.service(db)) {
            NodeProcessCrashTest.assertState(reference, actual);
            // Flat-file payloads are append-only. A failed RocksDB commit may leave an
            // unreachable blk/rev record, so a successful retry can legitimately publish
            // a different file offset than a clean reference database. Compare every
            // non-payload RocksDB key byte-for-byte and verify the block body semantically.
            assertContentsEqual(contentsWithoutFlatFilePositions(referenceDb),
                    contentsWithoutFlatFilePositions(db));
            var genesisHash = NetworkParametersRegistry.regtest().genesisBlockHash();
            assertArrayEquals(
                    ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(reference.findBlock(genesisHash).orElseThrow()),
                    ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(actual.findBlock(genesisHash).orElseThrow()));
            assertEquals(genesisHash,
                    new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
        }
    }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedSpendingCommitIsAtomicAndRetryable(Status.SubCode code) throws Exception { verifyTransition("spend", code); }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedDisconnectDoesNotMarkBranchInvalid(Status.SubCode code) throws Exception { verifyTransition("disconnect", code); }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedReconnectDoesNotClearInvalidation(Status.SubCode code) throws Exception { verifyTransition("reconnect", code); }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedForkSwitchPreservesOriginalChain(Status.SubCode code) throws Exception { verifyTransition("fork", code); }

    private void verifyTransition(String scenario, Status.SubCode code) throws Exception {
        Path path = directory.resolve("actual");
        try (var referenceDb = new RocksDbDatabase(directory.resolve("reference"));
             var reference = NodeProcessCrashTest.service(referenceDb)) {
            var referenceAction = NodeProcessCrashTest.prepare(reference, referenceDb, scenario, directory.resolve("reference-fork"));
            try (var db = new RocksDbDatabase(path); var actual = NodeProcessCrashTest.service(db)) {
                var action = NodeProcessCrashTest.prepare(actual, db, scenario, directory.resolve("actual-fork"));
                var before = contents(db);
                var generations = generations(db);
                try (var fault = new WriteFailure(db, code)) {
                    var thrown = assertThrows(IllegalStateException.class, () -> action.apply(actual));
                    assertSame(fault.error, thrown.getCause());
                    fault.assertUsed();
                    NodeProcessCrashTest.assertState(reference, actual);
                    assertContentsEqual(before, contents(db));
                    assertArrayEquals(generations, generations(db), "Failed writes must not publish cache generations");
                    if (action.failureRoot() != null)
                        assertEquals(reference.isBlockFailed(action.failureRoot()), actual.isBlockFailed(action.failureRoot()));
                    if (action.block() != null) {
                        assertTrue(new RocksDbBlockIndexStore(db).find(action.block().hash()).isEmpty(),
                                "Rejected new-block commit must not publish its index");
                    }
                }
                action.apply(actual);
                referenceAction.apply(reference);
                NodeProcessCrashTest.assertState(reference, actual);
            }
            try (var db = new RocksDbDatabase(path); var reopened = NodeProcessCrashTest.service(db)) {
                NodeProcessCrashTest.assertState(reference, reopened);
                var tip = reopened.activeTip().hash();
                reopened.invalidateBlock(tip);
                reference.invalidateBlock(tip);
                NodeProcessCrashTest.assertState(reference, reopened);
                reopened.reconsiderBlock(tip);
                reference.reconsiderBlock(tip);
                NodeProcessCrashTest.assertState(reference, reopened);
            }
        }
    }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedHeaderBatchDoesNotPublishBestHeader(Status.SubCode code) throws Exception {
        Path path = directory.resolve("headers");
        ru.bitcoin.node.common.types.Hash256 expected;
        try (var db = new RocksDbDatabase(path); var validation = NodeProcessCrashTest.service(db)) {
            var action = NodeProcessCrashTest.prepare(validation, db, "coinbase", directory.resolve("unused-fork"));
            var indexes = new RocksDbBlockIndexStore(db);
            var state = new HeaderChainState(validation.activeTip());
            var processor = new HeaderBatchProcessor(new HeaderProcessor(new StoredBlockIndexLookup(indexes),
                    NetworkParametersRegistry.regtest(), () -> 1_800_000_000L), state,
                    new KnownHeaderStorage(db, indexes, new RocksDbChainStateStore(db)));
            var before = contents(db);
            var generations = generations(db);
            var oldTip = state.bestHeaderTip().hash();
            var headers = List.of(action.block().header());
            try (var fault = new WriteFailure(db, code)) {
                var thrown = assertThrows(IllegalStateException.class, () -> processor.process(headers));
                assertSame(fault.error, thrown.getCause());
                fault.assertUsed();
                assertEquals(oldTip, state.bestHeaderTip().hash());
                assertContentsEqual(before, contents(db));
                assertArrayEquals(generations, generations(db));
            }
            processor.process(headers);
            expected = action.block().hash();
            assertEquals(expected, state.bestHeaderTip().hash());
        }
        try (var db = new RocksDbDatabase(path)) {
            assertEquals(expected, new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
            assertTrue(new RocksDbBlockIndexStore(db).findSkipHash(expected).isPresent());
        }
    }

    @ParameterizedTest @EnumSource(value = Status.SubCode.class, names = {"None", "NoSpace"})
    void failedCachedUtxoTransitionPreservesCoinsAndRetryPublishesCache(Status.SubCode code) throws Exception {
        Path path = directory.resolve("cached-transition");
        var oldPoint = new ru.bitcoin.node.protocol.transaction.OutPoint(Hash256.fromDisplayHex("11".repeat(32)),
                new ru.bitcoin.node.common.types.UInt32(0));
        var newPoint = new ru.bitcoin.node.protocol.transaction.OutPoint(Hash256.fromDisplayHex("22".repeat(32)),
                new ru.bitcoin.node.common.types.UInt32(1));
        var oldCoin = new ru.bitcoin.node.storage.utxo.StoredUtxo(10_000, new byte[]{0x51}, 1, false);
        var newCoin = new ru.bitcoin.node.storage.utxo.StoredUtxo(9_000, new byte[]{0x52}, 2, false);
        Hash256 newTip;
        try (var db = new RocksDbDatabase(path)) {
            var genesis = new ChainInitializer(db, NetworkParametersRegistry.regtest()).initialize().activeTip();
            var header = genesis.header();
            var child = BlockIndexFactory.createChild(genesis, new ru.bitcoin.node.protocol.block.BlockHeader(
                    4, genesis.hash(), header.merkleRoot(), new ru.bitcoin.node.common.types.UInt32(header.timestamp().value() + 1),
                    header.bits(), new ru.bitcoin.node.common.types.UInt32(0)));
            newTip = child.hash();
            var indexes = new RocksDbBlockIndexStore(db);
            indexes.save(BlockIndexStorageMapper.toStored(child));
            var tips = new RocksDbChainStateStore(db);
            var coins = new ru.bitcoin.node.storage.utxo.RocksDbUtxoStore(db, RocksDbNamespaces.UTXO, 2);
            var undo = new ru.bitcoin.node.storage.undo.RocksDbUndoStore(db);
            coins.save(oldPoint, oldCoin);
            assertEquals(oldCoin, coins.find(oldPoint).orElseThrow());
            assertTrue(coins.cacheStats().hits() > 0);
            var changes = new ru.bitcoin.node.chain.utxo.BlockReorganizationChanges(
                    new ru.bitcoin.node.storage.utxo.UtxoChanges(List.of(oldPoint),
                            List.of(new ru.bitcoin.node.storage.utxo.CreatedUtxo(newPoint, newCoin))),
                    java.util.Map.of(newTip, new ru.bitcoin.node.storage.undo.BlockUndoData(List.of(
                            new ru.bitcoin.node.storage.undo.TransactionUndo(List.of(oldCoin))))));
            var storage = new ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage(db, coins, undo, indexes, tips);
            // Refill the old cache after batch preparation, just before native write.
            java.util.function.Consumer<RocksDbWriteBatch> readBeforeCommit = batch -> {
                assertEquals(oldCoin, coins.find(oldPoint).orElseThrow());
                assertTrue(coins.find(newPoint).isEmpty());
            };
            var before = contents(db);
            var versions = generations(db);
            try (var fault = new WriteFailure(db, code)) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> storage.commit(genesis.hash(), child.hash(), changes, readBeforeCommit));
                assertSame(fault.error, thrown.getCause());
                fault.assertUsed();
                assertContentsEqual(before, contents(db));
                assertArrayEquals(versions, generations(db));
                assertEquals(oldCoin, coins.find(oldPoint).orElseThrow());
                assertTrue(coins.find(newPoint).isEmpty());
                assertEquals(genesis.hash(), tips.loadActiveTipHash().orElseThrow());
                assertTrue(undo.find(newTip).isEmpty());
            }
            storage.commit(genesis.hash(), newTip, changes, readBeforeCommit);
            assertTrue(coins.find(oldPoint).isEmpty());
            assertEquals(newCoin, coins.find(newPoint).orElseThrow());
            assertEquals(newTip, tips.loadActiveTipHash().orElseThrow());
            assertEquals(changes.connectedBlockUndo().get(newTip), undo.find(newTip).orElseThrow());
            assertTrue(coins.cacheStats().size() <= 2);
        }
        try (var db = new RocksDbDatabase(path)) {
            var coins = new ru.bitcoin.node.storage.utxo.RocksDbUtxoStore(db);
            assertTrue(coins.find(oldPoint).isEmpty());
            assertEquals(newCoin, coins.find(newPoint).orElseThrow());
            assertEquals(newTip, new RocksDbChainStateStore(db).loadActiveTipHash().orElseThrow());
            assertTrue(new ru.bitcoin.node.storage.undo.RocksDbUndoStore(db).find(newTip).isPresent());
        }
    }
    private static List<String> contentsWithoutFlatFilePositions(RocksDbDatabase db) {
        var result = new ArrayList<String>();
        var hex = HexFormat.of();
        for (int i = 0; i < 256; i++) {
            // 0x04 = undo position, 0x05 = block position. Their offsets are physical
            // append locations and are intentionally not transactional with RocksDB.
            if (i == 0x04 || i == 0x05) continue;
            db.visitPrefixAscending((byte) i, (key, value) -> {
                result.add(hex.formatHex(key) + ":" + hex.formatHex(value));
                return true;
            });
        }
        return result;
    }

    private static List<String> contents(RocksDbDatabase db) {
        var result = new ArrayList<String>();
        var hex = HexFormat.of();
        for (int i = 0; i < 256; i++) db.visitPrefixAscending((byte) i, (key, value) -> {
            result.add(hex.formatHex(key) + ":" + hex.formatHex(value));
            return true;
        });
        return result;
    }

    private static void assertContentsEqual(List<String> before, List<String> after) {
        assertTrue(before.equals(after), () -> "Database changed after rejected write; removed="
                + before.stream().filter(value -> !after.contains(value)).limit(3).toList()
                + "; added=" + after.stream().filter(value -> !before.contains(value)).limit(3).toList());
    }

    private static long[] generations(RocksDbDatabase db) {
        var result = new long[256];
        for (int i = 0; i < result.length; i++) result[i] = db.namespaceVersion((byte) i);
        return result;
    }

    private static final class WriteFailure implements AutoCloseable {
        private final RocksDbDatabase db;
        private final RocksDB original;
        private final RocksDB proxy;
        private final RocksDBException error;
        private final boolean singlePut;
        private final int failAt;

        WriteFailure(RocksDbDatabase db, Status.SubCode code) throws RocksDBException {
            this(db, code, false);
        }

        WriteFailure(RocksDbDatabase db, Status.SubCode code, boolean singlePut) throws RocksDBException {
            this(db, code, singlePut, 1);
        }

        WriteFailure(RocksDbDatabase db, Status.SubCode code, boolean singlePut, int failAt) throws RocksDBException {
            this.db = db;
            this.singlePut = singlePut;
            this.failAt = failAt;
            original = (RocksDB) ReflectionTestUtils.getField(db, "database");
            assertNotNull(original);
            proxy = mock(RocksDB.class, delegatesTo(original));
            error = new RocksDBException(new Status(Status.Code.IOError, code, "Injected write failure"));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                if (calls.incrementAndGet() == failAt) throw error;
                original.write(invocation.getArgument(0, WriteOptions.class), invocation.getArgument(1, WriteBatch.class));
                return null;
            }).when(proxy).write(any(WriteOptions.class), any(WriteBatch.class));
            if (singlePut) doThrow(error).when(proxy).put(any(byte[].class), any(byte[].class));
            // Test-only seam: production RocksDbDatabase still performs wrapping and generation publication.
            ReflectionTestUtils.setField(db, "database", proxy);
        }

        void assertUsed() throws RocksDBException {
            if (singlePut) verify(proxy, times(1)).put(any(byte[].class), any(byte[].class));
            else verify(proxy, times(failAt)).write(any(WriteOptions.class), any(WriteBatch.class));
        }

        @Override public void close() { ReflectionTestUtils.setField(db, "database", original); }
    }
}
