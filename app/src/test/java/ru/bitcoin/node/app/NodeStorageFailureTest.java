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

        WriteFailure(RocksDbDatabase db, Status.SubCode code) throws RocksDBException {
            this.db = db;
            original = (RocksDB) ReflectionTestUtils.getField(db, "database");
            assertNotNull(original);
            proxy = mock(RocksDB.class, delegatesTo(original));
            error = new RocksDBException(new Status(Status.Code.IOError, code, "Injected write failure"));
            doThrow(error).when(proxy).write(any(WriteOptions.class), any(WriteBatch.class));
            // Test-only seam: production RocksDbDatabase still performs wrapping and generation publication.
            ReflectionTestUtils.setField(db, "database", proxy);
        }

        void assertUsed() throws RocksDBException {
            verify(proxy, times(1)).write(any(WriteOptions.class), any(WriteBatch.class));
        }

        @Override public void close() { ReflectionTestUtils.setField(db, "database", original); }
    }
}
