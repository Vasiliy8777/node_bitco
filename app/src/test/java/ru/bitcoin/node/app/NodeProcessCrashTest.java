package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.chain.StoredBlockIndexLookup;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.BlockTemplateBuilder;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real child JVM, actual application wiring, forced exit without shutdown hooks or DB close. */
class NodeProcessCrashTest {
    @TempDir Path directory;
    private static final int BASE_HEIGHT = 6;

    @Test void killBeforeSubmissionPreservesCommittedState() throws Exception { verifyCrash("before"); }
    @Test void killAfterSubmissionPreservesAcknowledgedState() throws Exception { verifyCrash("after"); }
    @Test void killRacingSubmissionRecoversACompleteState() throws Exception { verifyCrash("racing"); }

    private void verifyCrash(String phase) throws Exception {
        Path data = directory.resolve("crashed");
        Path ready = directory.resolve("ready");
        Path committed = directory.resolve("committed");
        Path log = directory.resolve("child.log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(executable, "-cp", classpath, CrashWorker.class.getName(),
                data.toString(), ready.toString(), committed.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            awaitMarker(process, ready, log);
            if (!phase.equals("before")) {
                process.getOutputStream().write(1);
                process.getOutputStream().flush();
                if (phase.equals("after")) awaitMarker(process, committed, log);
            }
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child did not terminate");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child cleanup failed");
            }
        }

        awaitLockFileRelease(data);
        try (var db = new RocksDbDatabase(data);
             var actual = service(db);
             var referenceDb = new RocksDbDatabase(directory.resolve("reference"));
             var reference = service(referenceDb)) {
            long height = actual.activeTip().height();
            if (phase.equals("before")) assertEquals(BASE_HEIGHT, height);
            else if (phase.equals("after")) assertEquals(BASE_HEIGHT + 1, height);
            else assertTrue(height == BASE_HEIGHT || height == BASE_HEIGHT + 1, "Torn tip: " + height);
            for (int i = 0; i < height; i++) connect(reference, referenceDb);
            assertState(reference, actual);
            assertEquals(actual.activeTip().hash(), new RocksDbChainStateStore(db).loadActiveTipHash().orElseThrow());
            var indexes = new RocksDbBlockIndexStore(db);
            var undo = new RocksDbUndoStore(db);
            var cursor = actual.activeTip();
            while (cursor.height() > 0) {
                var hash = cursor.hash();
                assertArrayEquals(BlockSerializer.serialize(reference.findBlock(hash).orElseThrow()),
                        BlockSerializer.serialize(actual.findBlock(hash).orElseThrow()));
                assertTrue(undo.find(hash).isPresent(), "Missing undo at " + cursor.height());
                cursor = new StoredBlockIndexLookup(indexes).find(cursor.previousBlockHash());
                assertNotNull(cursor);
            }
            // Exercise recovered undo and subsequent activation, not just readable tip metadata.
            var tip = actual.activeTip().hash();
            actual.invalidateBlock(tip);
            reference.invalidateBlock(tip);
            assertState(reference, actual);
            actual.reconsiderBlock(tip);
            reference.reconsiderBlock(tip);
            assertState(reference, actual);
            connect(actual, db);
            connect(reference, referenceDb);
            assertState(reference, actual);
        }
    }

    private static NodeValidationService service(RocksDbDatabase db) {
        new ru.bitcoin.node.chain.ChainstateConsistencyChecker(db, NetworkParametersRegistry.regtest(),
                ru.bitcoin.node.chain.ChainstateConsistencyChecker.DEFAULT_REORG_SAFETY_DEPTH).verify();
        return new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
    }

    private static void awaitLockFileRelease(Path data) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            // Probe only Windows file sharing. Never retry RocksDB recovery or modify LOCK.
            try (var ignored = java.nio.channels.FileChannel.open(data.resolve("LOCK"),
                    java.nio.file.StandardOpenOption.WRITE)) { return; }
            catch (java.nio.file.FileSystemException exception) {
                if (System.nanoTime() >= deadline) throw exception;
                Thread.sleep(10);
            }
        }
    }

    private static void assertState(NodeValidationService expected, NodeValidationService actual) {
        assertEquals(expected.activeTip().hash(), actual.activeTip().hash());
        assertEquals(expected.activeTip().chainWork(), actual.activeTip().chainWork());
        var left = expected.utxoSetInfo();
        var right = actual.utxoSetInfo();
        assertEquals(left.hashSerialized3(), right.hashSerialized3());
        assertEquals(left.txouts(), right.txouts());
        assertEquals(left.totalAmount(), right.totalAmount());
    }

    private static Block candidate(NodeValidationService service, RocksDbDatabase db) {
        var parent = service.activeTip();
        var parameters = NetworkParametersRegistry.regtest();
        var template = BlockTemplateBuilder.build(parent, new StoredBlockIndexLookup(new RocksDbBlockIndexStore(db)),
                new RocksDbUtxoStore(db), parameters, 4, new UInt32(parent.header().timestamp().value() + 1),
                parent.header().bits(), new byte[]{0x51}, new byte[16], List.of());
        return NonceMiner.search(template, parameters, 0, 100_000, () -> false).orElseThrow();
    }

    private static void connect(NodeValidationService service, RocksDbDatabase db) {
        if (service.processBlock(candidate(service, db)) != BlockProcessingResult.CONNECTED)
            throw new IllegalStateException("Fixture block did not connect");
    }

    private static void awaitMarker(Process process, Path marker, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(marker), () -> {
            try { return "Child did not reach " + marker.getFileName() + ":\n" + Files.readString(log); }
            catch (Exception exception) { return exception.toString(); }
        });
    }

    public static final class CrashWorker {
        public static void main(String[] args) throws Exception {
            // CLI properties override every packaged profile and local environment default.
            try (var context = new SpringApplicationBuilder(BitcoinNodeApplication.class)
                    .web(WebApplicationType.NONE).run("--spring.profiles.active=regtest",
                            "--bitcoin.network=regtest", "--bitcoin.data-directory=" + args[0],
                            "--bitcoin.node.auto-start=false", "--bitcoin.p2p.listen=false",
                            "--bitcoin.rpc.enabled=false", "--bitcoin.stratum.enabled=false")) {
                var db = context.getBean(RocksDbDatabase.class);
                var validation = context.getBean(NodeValidationService.class);
                for (int i = 0; i < BASE_HEIGHT; i++) connect(validation, db);
                Block next = candidate(validation, db);
                Files.writeString(Path.of(args[1]), "ready");
                if (System.in.read() == -1) throw new IllegalStateException("Parent closed barrier");
                if (validation.processBlock(next) != BlockProcessingResult.CONNECTED)
                    throw new IllegalStateException("Fixture block did not connect");
                Files.writeString(Path.of(args[2]), "committed");
                // Parent must force termination here; normal shutdown is a test failure.
                System.in.read();
                throw new IllegalStateException("Parent did not force termination");
            }
        }
    }
}
