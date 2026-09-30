package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;
import org.rocksdb.WriteBatch;
import org.springframework.test.util.ReflectionTestUtils;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.*;
import ru.bitcoin.node.storage.utxo.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Deterministic process termination between real native commits during startup promotion. */
class SnapshotProcessCrashTest {
    @TempDir Path directory;
    @Test void killBeforePromotion() throws Exception { verifyCrash(0); }
    @Test void killAfterCanonicalClear() throws Exception { verifyCrash(1); }
    @Test void killAfterFirstCopyBatch() throws Exception { verifyCrash(2); }
    @Test void killAfterLastCopyBatch() throws Exception { verifyCrash(3); }
    @Test void killAfterCleanupCommit() throws Exception { verifyCrash(4); }

    private void verifyCrash(int boundary) throws Exception {
        Path data = directory.resolve("actual");
        try (var db = new RocksDbDatabase(data); var node = service(db)) {
            prepare(node);
        }
        try (var db = new RocksDbDatabase(data)) {
            var tips = new RocksDbChainStateStore(db);
            var advanced = tips.loadActiveTipHash().orElseThrow();
            var indexes = new ru.bitcoin.node.storage.block.RocksDbBlockIndexStore(db);
            var base = indexes.find(advanced).orElseThrow().previousBlockHash();
            var genesis = NetworkParametersRegistry.regtest().genesisBlockHash();
            try (var batch = new RocksDbWriteBatch()) {
                db.forEachEntryByPrefix(RocksDbNamespaces.UTXO, (key, value) -> {
                    byte[] staging = key.clone();
                    staging[0] = RocksDbNamespaces.SNAPSHOT_UTXO_STAGING;
                    batch.put(staging, value);
                });
                batch.deletePrefix(RocksDbNamespaces.UTXO);
                db.write(batch);
            }
            tips.saveActiveTipHash(genesis);
            new RocksDbSnapshotChainStateStore(db).activate(genesis, 0, base, 1);
            tips.saveActiveTipHash(advanced); // Snapshot chain has advanced beyond the validated base.
            var background = new RocksDbAssumeUtxoBackgroundStore(db);
            background.initialize(genesis, 0);
            background.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, base, 1);
        }
        Path marker = directory.resolve("boundary");
        Path log = directory.resolve("child.log");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(java, "-cp", classpath, Worker.class.getName(),
                data.toString(), marker.toString(), Integer.toString(boundary))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            NodeProcessCrashTest.awaitMarker(process, marker, log);
            assertEquals(Integer.toString(boundary), Files.readString(marker));
            assertTrue(process.isAlive(), "Worker must remain paused with database open");
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            }
        }
        NodeProcessCrashTest.awaitLockFileRelease(data);
        try (var db = new RocksDbDatabase(data)) {
            assertEquals(boundary < 4, new RocksDbSnapshotChainStateStore(db).load().isPresent());
            assertEquals(boundary < 4, new RocksDbAssumeUtxoBackgroundStore(db).load().isPresent());
            assertEquals(boundary == 1 || boundary == 2 || boundary == 3,
                    db.get(new byte[]{RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE, 1}) != null);
            long[] count = {0};
            db.forEachEntryByPrefix(RocksDbNamespaces.UTXO, (key, value) -> count[0]++);
            assertEquals(boundary < 2 ? 0 : boundary == 2 ? 10_000 : 10_002, count[0]);
            try (var actual = service(db); var referenceDb = new RocksDbDatabase(directory.resolve("reference"));
                 var reference = service(referenceDb)) {
                prepare(reference);
                new ChainstateConsistencyChecker(db, NetworkParametersRegistry.regtest(),
                        ChainstateConsistencyChecker.DEFAULT_REORG_SAFETY_DEPTH).verify();
                NodeProcessCrashTest.assertState(reference, actual);
                assertEquals(10_002, actual.utxoSetInfo().txouts());
                assertTrue(new RocksDbSnapshotChainStateStore(db).load().isEmpty());
                assertTrue(new RocksDbAssumeUtxoBackgroundStore(db).load().isEmpty());
                assertFalse(new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup());
                var tip = actual.activeTip().hash();
                actual.invalidateBlock(tip);
                reference.invalidateBlock(tip);
                NodeProcessCrashTest.assertState(reference, actual);
                actual.reconsiderBlock(tip);
                reference.reconsiderBlock(tip);
                NodeProcessCrashTest.assertState(reference, actual);
                connect(actual, false);
                connect(reference, false);
                NodeProcessCrashTest.assertState(reference, actual);
            }
        }
        try (var db = new RocksDbDatabase(data); var reopened = service(db)) {
            assertEquals(3, reopened.activeTip().height());
            assertEquals(10_003, reopened.utxoSetInfo().txouts());
        }
    }

    private static NodeValidationService service(RocksDbDatabase db) {
        return new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
    }

    private static void prepare(NodeValidationService node) {
        connect(node, true);
        connect(node, false);
        assertEquals(10_002, node.utxoSetInfo().txouts());
    }

    private static void connect(NodeValidationService node, boolean fanout) {
        var block = node.createMiningTemplate(new byte[]{0x51}, new byte[0], 4_000_000, new FeeRate(0));
        if (fanout) {
            var original = block.transactions().getFirst();
            var outputs = new ArrayList<TxOut>();
            for (int i = 0; i < 10_001; i++) outputs.add(new TxOut(1, new byte[]{0x51}));
            // Preserve the witness commitment and reserved-value witness from the validated template.
            outputs.addAll(original.outputs().subList(1, original.outputs().size()));
            var coinbase = new Transaction(original.version(), original.inputs(), outputs, original.lockTime());
            var header = block.header();
            block = new Block(new BlockHeader(header.version(), header.previousBlockHash(), coinbase.txId(),
                    header.timestamp(), header.bits(), new UInt32(0)), List.of(coinbase));
        }
        assertEquals(BlockProcessingResult.CONNECTED, node.processBlock(
                NonceMiner.search(block, NetworkParametersRegistry.regtest(), 0, 100_000, () -> false).orElseThrow()));
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            int stopAfter = Integer.parseInt(args[2]);
            try (var db = new RocksDbDatabase(Path.of(args[0]))) {
                var nativeDb = (RocksDB) ReflectionTestUtils.getField(db, "database");
                assertNotNull(nativeDb);
                var proxy = mock(RocksDB.class, delegatesTo(nativeDb));
                var writes = new AtomicInteger();
                doAnswer(call -> {
                    nativeDb.write(call.getArgument(0, WriteOptions.class), call.getArgument(1, WriteBatch.class));
                    if (writes.incrementAndGet() == stopAfter) pause(Path.of(args[1]), stopAfter);
                    return null;
                }).when(proxy).write(any(WriteOptions.class), any(WriteBatch.class));
                ReflectionTestUtils.setField(db, "database", proxy);
                if (stopAfter == 0) pause(Path.of(args[1]), 0);
                service(db);
                throw new IllegalStateException("Startup passed the requested crash boundary");
            }
        }

        private static void pause(Path marker, int boundary) throws Exception {
            Files.writeString(marker, Integer.toString(boundary));
            System.in.read();
            throw new IllegalStateException("Parent must forcibly terminate worker");
        }
    }
}
