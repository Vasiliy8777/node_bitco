package ru.bitcoin.node.rvn.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Storage mechanics only; synthetic deltas do not claim consensus validity. */
class RvnCoinStoreTest {
    @TempDir Path temp;
    @Test void validationCoverageIsAtomicContiguousAndSurvivesReorgAndRestart() throws Exception {
        var path = temp.resolve("coverage");
        var empty = new RvnCoinAccounting.Changes(0, Map.of(), Map.of());
        try (var store = new RvnCoinStore(path, id(0))) {
            assertThrows(IOException.class, () -> store.connectValidated(store.tip(), id(1), empty,
                    ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY, "v1", false));
            store.anchorValidation("v1");
            store.connectValidated(store.tip(), id(1), empty, ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY, "v1", false);
            assertEquals(store.tip(), store.validation().orElseThrow().tip());
            assertThrows(IllegalStateException.class, () -> store.anchorValidation("v2"));
            assertThrows(IOException.class, () -> store.connectValidated(store.tip(), id(2), empty,
                    ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY, "v2", false));
        }
        try (var store = new RvnCoinStore(path, id(0))) {
            assertEquals("v1", store.validation().orElseThrow().version());
            store.connectValidated(store.tip(), id(2), empty, ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY, "v1", true);
            assertEquals(Optional.of(store.tip()), store.pendingMinedBlock());
            assertEquals(store.tip(), store.validation().orElseThrow().tip());
            store.disconnect(store.tip());
            assertTrue(store.pendingMinedBlock().isEmpty()); assertEquals(store.tip(), store.validation().orElseThrow().tip());
            store.connect(store.tip(), id(3), empty); // A generic/old writer cannot extend validation coverage.
            assertTrue(store.validation().isEmpty());
            store.disconnect(store.tip()); store.disconnect(store.tip());
            store.anchorValidation("v2"); assertEquals(0, store.validation().orElseThrow().tip().height());
        }
    }
    static RvnHash256 id(int marker) { byte[] bytes = new byte[32]; bytes[0] = (byte) marker; return new RvnHash256(bytes); }
    static OutPoint point(int marker) { return new OutPoint(new Hash256(id(marker).wire()), new UInt32(0)); }
    static RvnCoin coin(long amount, long height) { return new RvnCoin(new TxOut(amount, new byte[]{0x51}), height, false); }
    static RvnCoinAccounting.Changes changes(Map<OutPoint, RvnCoin> spent, Map<OutPoint, RvnCoin> created) {
        return new RvnCoinAccounting.Changes(0, spent, created);
    }
    RvnCoinStore open(Path directory) throws IOException { return new RvnCoinStore(directory, id(0)); }

    @Test void minedOutboxCommitsWithCoinsSurvivesRestartAndIsRemovedOnlyByAckOrItsDisconnect() throws Exception {
        Path directory = temp.resolve("mined");
        var first = new RvnCoinStore.Tip(id(1), 1);
        try (var store = open(directory)) {
            assertTrue(store.pendingMinedBlock().isEmpty());
            store.connectMined(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))),
                    ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY);
            assertEquals(Optional.of(first), store.pendingMinedBlock());
        }
        try (var store = open(directory)) {
            assertEquals(first, store.tip()); assertEquals(Optional.of(first), store.pendingMinedBlock());
            assertThrows(IllegalStateException.class, () -> store.connectMined(store.tip(), id(2), changes(Map.of(), Map.of()),
                    ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY));
            assertFalse(store.acknowledgeMinedBlock(new RvnCoinStore.Tip(id(9), 1)));
            store.connect(store.tip(), id(2), changes(Map.of(), Map.of()));
            store.disconnect(store.tip()); // A descendant reorg must retain the first candidate.
            assertEquals(Optional.of(first), store.pendingMinedBlock());
            assertTrue(store.acknowledgeMinedBlock(first)); assertTrue(store.pendingMinedBlock().isEmpty());
            store.connectMined(store.tip(), id(3), changes(Map.of(), Map.of(point(3), coin(900, 2))),
                    ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY);
            store.disconnect(store.tip()); // Its own reorg clears the marker atomically.
            assertTrue(store.pendingMinedBlock().isEmpty()); assertEquals(first, store.tip());
        }
        try (var store = open(directory)) {
            assertTrue(store.pendingMinedBlock().isEmpty()); assertEquals(first, store.tip());
        }
    }

    @Test
    void restartDisconnectAndAlternativeBranchRestoreExactCoins() throws Exception {
        Path directory = temp.resolve("coins");
        RvnCoinStore.Tip tip2;
        try (var store = open(directory)) {
            assertEquals(new RvnCoinStore.Tip(id(0), 0), store.tip());
            assertTrue(store.find(point(1)).isEmpty()); // No spendable genesis reward.
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
            tip2 = store.connect(store.tip(), id(2), changes(Map.of(point(1), coin(1000, 1)), Map.of(point(2), coin(900, 2))));
            assertThrows(IllegalArgumentException.class, () -> store.connect(store.tip(), id(1), changes(Map.of(), Map.of())));
            assertTrue(store.find(point(1)).isEmpty());
        }
        try (var store = open(directory)) {
            assertEquals(tip2, store.tip());
            assertEquals(Optional.of(coin(900, 2)), store.find(point(2)));
            assertEquals(new RvnCoinStore.Tip(id(1), 1), store.disconnect(tip2));
            assertEquals(Optional.of(coin(1000, 1)), store.find(point(1)));
            assertTrue(store.find(point(2)).isEmpty());
            store.connect(store.tip(), id(3), changes(Map.of(point(1), coin(1000, 1)), Map.of(point(3), coin(800, 2))));
        }
        try (var store = open(directory)) {
            assertEquals(id(3), store.tip().id());
            assertEquals(Optional.of(coin(800, 2)), store.find(point(3)));
            store.disconnect(store.tip()); store.disconnect(store.tip());
            assertEquals(new RvnCoinStore.Tip(id(0), 0), store.tip());
            assertTrue(store.find(point(1)).isEmpty());
            assertThrows(IllegalStateException.class, () -> store.disconnect(store.tip()));
        }
    }

    @Test
    void staleParentOrInvalidDeltaMakesNoPartialChanges() throws Exception {
        Path directory = temp.resolve("coins");
        try (var store = open(directory)) {
            var genesis = store.tip();
            var first = store.connect(genesis, id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
            assertThrows(IllegalStateException.class, () -> store.connect(genesis, id(2), changes(Map.of(), Map.of())));
            assertThrows(IllegalStateException.class, () -> store.disconnect(genesis));
            assertThrows(SecurityException.class, () -> store.connect(first, id(2), changes(
                    Map.of(point(1), coin(999, 1)), Map.of(point(2), coin(900, 2)))));
            assertThrows(SecurityException.class, () -> store.connect(first, id(2), changes(
                    Map.of(), Map.of(point(1), coin(1000, 2)))));
            assertThrows(SecurityException.class, () -> store.connect(first, id(2), changes(
                    Map.of(), Map.of(point(2), coin(900, 3)))));
            assertThrows(IllegalArgumentException.class, () -> store.connect(first, id(2), changes(
                    Map.of(point(1), coin(1000, 1)), Map.of(point(1), coin(900, 2)))));
            assertEquals(first, store.tip());
            assertEquals(Optional.of(coin(1000, 1)), store.find(point(1)));
            assertTrue(store.find(point(2)).isEmpty());
        }
        try (var store = open(directory)) { assertEquals(new RvnCoinStore.Tip(id(1), 1), store.tip()); }
    }

    @Test
    void refusesForeignDirectoriesNetworkMismatchAndConcurrentOpen() throws Exception {
        Path directory = temp.resolve("coins"), foreign = temp.resolve("bitcoin");
        Files.createDirectories(foreign); Files.writeString(foreign.resolve("CURRENT"), "untouched");
        assertThrows(IOException.class, () -> open(foreign));
        assertEquals("untouched", Files.readString(foreign.resolve("CURRENT")));
        assertFalse(Files.exists(foreign.resolve("RVN_COINS")));
        try (var store = open(directory)) { assertThrows(IOException.class, () -> open(directory)); }
        assertThrows(IOException.class, () -> new RvnCoinStore(directory, id(9)));
        try (var store = open(directory)) { assertEquals(0, store.tip().height()); }
        Files.delete(directory.resolve("CURRENT"));
        assertThrows(IOException.class, () -> open(directory));
    }

    @Test
    void corruptUndoRefusesReopen() throws Exception {
        Path directory = temp.resolve("coins");
        try (var store = open(directory)) {
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
        }
        byte[] undoKey = new byte[33]; undoKey[0] = 3; undoKey[1] = 1;
        try (var options = new Options(); var db = RocksDB.open(options, directory.toString())) {
            byte[] undo = db.get(undoKey); undo[undo.length - 1] ^= 1; db.put(undoKey, undo);
        }
        assertThrows(IOException.class, () -> open(directory));
    }

    @Test
    void currentCoinsMustMatchUndoOnReopen() throws Exception {
        Path directory = temp.resolve("coins");
        try (var store = open(directory)) {
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
        }
        byte[] key = new byte[37]; key[0] = 2; key[1] = 1;
        try (var options = new Options(); var db = RocksDB.open(options, directory.toString())) {
            byte[] value = db.get(key); value[7] ^= 1; db.put(key, value);
        }
        assertThrows(IOException.class, () -> open(directory));
    }

    @Test
    void rejectsUnspendableCoinsAndReadsStopAfterClose() throws Exception {
        var store = open(temp.resolve("coins"));
        assertThrows(IOException.class, () -> store.connect(store.tip(), id(1), changes(Map.of(),
                Map.of(point(1), new RvnCoin(new TxOut(1000, new byte[]{0x6a}), 1, false)))));
        assertEquals(0, store.tip().height());
        store.close(); store.close();
        assertThrows(IllegalStateException.class, store::tip);
        assertThrows(IllegalStateException.class, () -> store.find(point(1)));
    }

    @Test
    void synchronousBatchSurvivesProcessHaltWithoutClose() throws Exception {
        Path directory = temp.resolve("crash-coins");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CrashWriter.class.getName(), directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Crash writer timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(17, process.exitValue(), output);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        try (var store = open(directory)) {
            assertEquals(new RvnCoinStore.Tip(id(1), 1), store.tip());
            assertEquals(Optional.of(coin(1000, 1)), store.find(point(1)));
            store.disconnect(store.tip());
            assertEquals(new RvnCoinStore.Tip(id(0), 0), store.tip());
            assertTrue(store.find(point(1)).isEmpty());
        }
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            var store = new RvnCoinStore(Path.of(args[0]), id(0));
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
            Runtime.getRuntime().halt(17);
        }
    }
}
