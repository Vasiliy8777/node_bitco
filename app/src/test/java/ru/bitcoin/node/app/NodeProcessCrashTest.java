package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.chain.StoredBlockIndexLookup;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.BlockTemplateBuilder;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.*;
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
    @Test void killBeforeSpendPreservesCoins() throws Exception { verifyCrash("before", "spend"); }
    @Test void killAfterSpendPreservesCoins() throws Exception { verifyCrash("after", "spend"); }
    @Test void killRacingSpendRecoversCompleteCoins() throws Exception { verifyCrash("racing", "spend"); }
    @Test void killBeforeDisconnectPreservesSpentChain() throws Exception { verifyCrash("before", "disconnect"); }
    @Test void killAfterDisconnectRestoresSpentCoins() throws Exception { verifyCrash("after", "disconnect"); }
    @Test void killRacingDisconnectRecoversCompleteChain() throws Exception { verifyCrash("racing", "disconnect"); }
    @Test void killBeforeReconnectPreservesRestoredCoins() throws Exception { verifyCrash("before", "reconnect"); }
    @Test void killAfterReconnectPreservesSpentCoins() throws Exception { verifyCrash("after", "reconnect"); }
    @Test void killRacingReconnectRecoversCompleteChain() throws Exception { verifyCrash("racing", "reconnect"); }
    @Test void killBeforeForkSwitchPreservesOriginalBranch() throws Exception { verifyCrash("before", "fork"); }
    @Test void killAfterForkSwitchPreservesWinningBranch() throws Exception { verifyCrash("after", "fork"); }
    @Test void killRacingForkSwitchRecoversOneCompleteBranch() throws Exception { verifyCrash("racing", "fork"); }

    private void verifyCrash(String phase) throws Exception {
        verifyCrash(phase, "coinbase");
    }

    private void verifyCrash(String phase, String scenario) throws Exception {
        Path data = directory.resolve("crashed");
        Path ready = directory.resolve("ready");
        Path committed = directory.resolve("committed");
        Path log = directory.resolve("child.log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(executable, "-cp", classpath, CrashWorker.class.getName(),
                data.toString(), ready.toString(), committed.toString(), scenario)
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
            var action = prepare(reference, referenceDb, scenario, directory.resolve("reference-fork"));
            var before = reference.activeTip().hash();
            if (phase.equals("after")) action.apply(reference);
            else if (phase.equals("racing") && !actual.activeTip().hash().equals(before)) action.apply(reference);
            assertState(reference, actual);
            if (action.failureRoot() != null) {
                assertEquals(reference.isBlockFailed(action.failureRoot()), actual.isBlockFailed(action.failureRoot()));
            }
            if (scenario.equals("fork")) {
                var coins = new RocksDbUtxoStore(db);
                boolean switched = actual.activeTip().hash().equals(action.block().hash());
                assertTrue(coins.find(action.points().get(0)).isEmpty(), "Common coin must be spent on either branch");
                assertTrue(coins.find(action.points().get(1)).isEmpty());
                assertTrue(coins.find(action.points().get(3)).isEmpty());
                assertEquals(!switched, coins.find(action.points().get(2)).isPresent());
                assertEquals(switched, coins.find(action.points().get(4)).isPresent());
                var surviving = coins.find(action.points().get(switched ? 4 : 2)).orElseThrow();
                assertEquals(switched ? 4_999_990_000L : 4_999_997_000L, surviving.amount());
                assertEquals(switched ? 103 : 102, surviving.height());
                assertFalse(surviving.coinbase());
            } else if (!action.points().isEmpty()) {
                var coins = new RocksDbUtxoStore(db);
                boolean spent = actual.activeTip().height() >= 102;
                assertEquals(!spent, coins.find(action.points().get(0)).isPresent());
                assertTrue(coins.find(action.points().get(1)).isEmpty(), "Intermediate output must never survive");
                assertEquals(spent, coins.find(action.points().get(2)).isPresent());
                var surviving = coins.find(action.points().get(spent ? 2 : 0)).orElseThrow();
                assertEquals(spent ? 4_999_997_000L : 5_000_000_000L, surviving.amount());
                assertEquals(spent ? 102 : 1, surviving.height());
                assertEquals(!spent, surviving.coinbase());
            }
            for (var point : action.points()) {
                // Directly verify spent/restored metadata as well as the aggregate digest.
                var expectedCoin = new RocksDbUtxoStore(referenceDb).find(point);
                var actualCoin = new RocksDbUtxoStore(db).find(point);
                assertEquals(expectedCoin.isPresent(), actualCoin.isPresent());
                if (expectedCoin.isPresent()) {
                    var left = expectedCoin.orElseThrow();
                    var right = actualCoin.orElseThrow();
                    assertEquals(left.amount(), right.amount());
                    assertEquals(left.height(), right.height());
                    assertEquals(left.coinbase(), right.coinbase());
                    assertArrayEquals(left.scriptPubKey(), right.scriptPubKey());
                }
            }
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
            if (scenario.equals("fork") && !actual.activeTip().hash().equals(action.block().hash())) {
                // Retry the winning block after recovery, including a possibly stored pending body.
                action.apply(actual);
                action.apply(reference);
                assertState(reference, actual);
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

    static NodeValidationService service(RocksDbDatabase db) {
        new ru.bitcoin.node.chain.ChainstateConsistencyChecker(db, NetworkParametersRegistry.regtest(),
                ru.bitcoin.node.chain.ChainstateConsistencyChecker.DEFAULT_REORG_SAFETY_DEPTH).verify();
        return new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
    }

    static void awaitLockFileRelease(Path data) throws Exception {
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

    static void assertState(NodeValidationService expected, NodeValidationService actual) {
        assertEquals(expected.activeTip().hash(), actual.activeTip().hash());
        assertEquals(expected.activeTip().chainWork(), actual.activeTip().chainWork());
        var left = expected.utxoSetInfo();
        var right = actual.utxoSetInfo();
        assertEquals(left.hashSerialized3(), right.hashSerialized3());
        assertEquals(left.txouts(), right.txouts());
        assertEquals(left.totalAmount(), right.totalAmount());
    }

    private static Block candidate(NodeValidationService service, RocksDbDatabase db) {
        return candidate(service, db, List.of());
    }

    private static Block candidate(NodeValidationService service, RocksDbDatabase db, List<Transaction> transactions) {
        var parent = service.activeTip();
        var parameters = NetworkParametersRegistry.regtest();
        var template = BlockTemplateBuilder.build(parent, new StoredBlockIndexLookup(new RocksDbBlockIndexStore(db)),
                new RocksDbUtxoStore(db), parameters, 4, new UInt32(parent.header().timestamp().value() + 1),
                parent.header().bits(), new byte[]{0x51}, new byte[16], transactions);
        return NonceMiner.search(template, parameters, 0, 100_000, () -> false).orElseThrow();
    }

    private static void connect(NodeValidationService service, RocksDbDatabase db) {
        if (service.processBlock(candidate(service, db)) != BlockProcessingResult.CONNECTED)
            throw new IllegalStateException("Fixture block did not connect");
    }

    record Action(Block block, Hash256 failureRoot, boolean reconnect, List<OutPoint> points) {
        void apply(NodeValidationService service) {
            if (block != null) {
                if (service.processBlock(block) != BlockProcessingResult.CONNECTED)
                    throw new IllegalStateException("Fixture block did not connect");
            } else if (reconnect) service.reconsiderBlock(failureRoot);
            else service.invalidateBlock(failureRoot);
        }
    }

    static Action prepare(NodeValidationService service, RocksDbDatabase db, String scenario, Path forkData) {
        if (scenario.equals("coinbase")) {
            for (int i = 0; i < BASE_HEIGHT; i++) connect(service, db);
            return new Action(candidate(service, db), null, false, List.of());
        }
        connect(service, db);
        var coinbase = service.findBlock(service.activeTip().hash()).orElseThrow().transactions().getFirst();
        for (int i = 1; i < 101; i++) connect(service, db);
        var root = service.activeTip().hash();
        var original = new OutPoint(coinbase.txId(), new UInt32(0));
        var first = spend(original, coinbase.outputs().getFirst().value() - 1000);
        var intermediate = new OutPoint(first.txId(), new UInt32(0));
        var second = spend(intermediate, first.outputs().getFirst().value() - 2000);
        var output = new OutPoint(second.txId(), new UInt32(0));
        var points = List.of(original, intermediate, output);
        // Same-block dependencies exercise both spending a durable coin and an overlay output.
        var spending = candidate(service, db, List.of(first, second));
        if (scenario.equals("spend")) return new Action(spending, null, false, points);
        if (!scenario.equals("disconnect") && !scenario.equals("reconnect") && !scenario.equals("fork"))
            throw new IllegalArgumentException("Unknown scenario " + scenario);
        if (service.processBlock(spending) != BlockProcessingResult.CONNECTED)
            throw new IllegalStateException("Fixture spending block did not connect");
        connect(service, db);
        if (scenario.equals("fork")) {
            var oldTip = service.activeTip();
            // Build the conflicting branch against its own UTXO view, not the active branch's coins.
            try (var forkDb = new RocksDbDatabase(forkData); var fork = service(forkDb)) {
                for (int i = 0; i < 101; i++) connect(fork, forkDb);
                if (!fork.activeTip().hash().equals(root)) throw new IllegalStateException("Fork point differs");
                var alternative = spend(original, 4_999_995_000L);
                var alternativePoint = new OutPoint(alternative.txId(), new UInt32(0));
                var descendant = spend(alternativePoint, 4_999_990_000L);
                var descendantPoint = new OutPoint(descendant.txId(), new UInt32(0));
                for (var transactions : List.of(List.of(alternative), List.of(descendant))) {
                    var block = candidate(fork, forkDb, transactions);
                    if (fork.processBlock(block) != BlockProcessingResult.CONNECTED)
                        throw new IllegalStateException("Fork fixture block did not connect");
                    if (service.processBlock(block) != BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING)
                        throw new IllegalStateException("Equal/lower-work branch activated prematurely");
                }
                if (!service.activeTip().hash().equals(oldTip.hash()))
                    throw new IllegalStateException("Original branch must remain active before barrier");
                var winning = candidate(fork, forkDb);
                if (fork.activeTip().chainWork().compareTo(oldTip.chainWork()) != 0)
                    throw new IllegalStateException("Fork fixture must have equal work before final block");
                return new Action(winning, null, false,
                        List.of(original, intermediate, output, alternativePoint, descendantPoint));
            }
        }
        // Invalidation at height 101 disconnects three blocks, including the dependent spends.
        if (scenario.equals("reconnect")) service.invalidateBlock(root);
        return new Action(null, root, scenario.equals("reconnect"), points);
    }

    private static Transaction spend(OutPoint input, long amount) {
        return new Transaction(2, List.of(new TxIn(input, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(amount, new byte[]{0x51})), new UInt32(0));
    }

    static void awaitMarker(Process process, Path marker, Path log) throws Exception {
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
                var action = prepare(validation, db, args[3], Path.of(args[0]).resolveSibling("fork"));
                Files.writeString(Path.of(args[1]), "ready");
                if (System.in.read() == -1) throw new IllegalStateException("Parent closed barrier");
                action.apply(validation);
                Files.writeString(Path.of(args[2]), "committed");
                // Parent must force termination here; normal shutdown is a test failure.
                System.in.read();
                throw new IllegalStateException("Parent did not force termination");
            }
        }
    }
}
