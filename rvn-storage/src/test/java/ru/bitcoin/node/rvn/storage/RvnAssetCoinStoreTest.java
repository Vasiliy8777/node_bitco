package ru.bitcoin.node.rvn.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import ru.bitcoin.node.rvn.chain.assets.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.HashData;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.storage.RvnCoinStoreTest.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;

/** Synthetic storage plans, not consensus-accepted asset blocks. */
class RvnAssetCoinStoreTest {
    @TempDir Path temp;
    private static final Metadata FIRST = new Metadata("TOKEN", COIN, 0, true, new HashData(0x12, "ab".repeat(32)));
    private static final Metadata SECOND = new Metadata("TOKEN", 2 * COIN, 8, false, new HashData(0x54, "cd".repeat(32)));
    private static RvnAssetChanges delta(Metadata before, Metadata after) {
        return new RvnAssetChanges(Map.of("TOKEN", new RvnAssetChanges.Update(Optional.ofNullable(before), Optional.ofNullable(after))));
    }
    private RvnCoinStore open(Path path) throws IOException { return new RvnCoinStore(path, id(0)); }

    @Test void metadataCoinsAndUndoSurviveRestartAndAlternativeBranch() throws Exception {
        Path db = temp.resolve("coins");
        try (var store = open(db)) {
            // Old native-only undo remains readable alongside asset-aware undo.
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))));
            store.connect(store.tip(), id(2), changes(Map.of(point(1), coin(1000, 1)), Map.of(point(2), coin(900, 2))), delta(null, FIRST));
            store.connect(store.tip(), id(3), changes(Map.of(), Map.of()), delta(FIRST, SECOND));
        }
        try (var store = open(db)) {
            assertEquals(Optional.of(SECOND), store.findAsset("TOKEN"));
            store.disconnect(store.tip());
            assertEquals(Optional.of(FIRST), store.findAsset("TOKEN"));
            store.disconnect(store.tip());
            assertTrue(store.findAsset("TOKEN").isEmpty());
            assertEquals(Optional.of(coin(1000, 1)), store.find(point(1)));
            assertTrue(store.find(point(2)).isEmpty());
            store.connect(store.tip(), id(4), changes(Map.of(), Map.of()), delta(null, SECOND));
        }
        try (var store = open(db)) {
            assertEquals(Optional.of(SECOND), store.findAsset("TOKEN"));
            store.disconnect(store.tip()); store.disconnect(store.tip());
            assertEquals(0, store.tip().height());
            assertTrue(store.findAsset("TOKEN").isEmpty());
        }
    }

    @Test void staleAssetPlanCannotPartiallyCommitCoinsOrOtherMetadata() throws Exception {
        try (var store = open(temp.resolve("coins"))) {
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of()), delta(null, FIRST));
            var stale = new RvnAssetChanges(Map.of(
                    "TOKEN", new RvnAssetChanges.Update(Optional.empty(), Optional.of(SECOND)),
                    "OTHER", new RvnAssetChanges.Update(Optional.empty(), Optional.of(new Metadata("OTHER", COIN, 0, true, null)))));
            assertThrows(SecurityException.class, () -> store.connect(store.tip(), id(2),
                    changes(Map.of(), Map.of(point(2), coin(1000, 2))), stale));
            assertEquals(id(1), store.tip().id());
            assertEquals(Optional.of(FIRST), store.findAsset("TOKEN"));
            assertTrue(store.findAsset("OTHER").isEmpty());
            assertTrue(store.find(point(2)).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new RvnAssetChanges(Map.of("OTHER",
                new RvnAssetChanges.Update(Optional.empty(), Optional.of(FIRST)))));
    }

    @Test void metadataTamperingIsDetectedAgainstUndoOnReopen() throws Exception {
        Path dbPath = temp.resolve("coins");
        try (var store = open(dbPath)) {
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of()), delta(null, FIRST));
        }
        try (var options = new Options(); var db = RocksDB.open(options, dbPath.toString())) {
            db.put(new byte[]{5, 'T', 'O', 'K', 'E', 'N'}, RvnAssetStateCodec.encode(SECOND));
        }
        assertThrows(IOException.class, () -> open(dbPath));
    }

    @Test void assetSchemaUpgradePersistsAfterNativeOnlyBlocksAndRollback() throws Exception {
        Path dbPath = temp.resolve("coins");
        try (var store = open(dbPath)) {
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of()), delta(null, FIRST));
            store.connect(store.tip(), id(2), changes(Map.of(), Map.of()));
        }
        try (var options = new Options(); var db = RocksDB.open(options, dbPath.toString())) {
            assertTrue(new String(db.get(new byte[]{0}), java.nio.charset.StandardCharsets.US_ASCII).startsWith("RVN-COINS-ASSETS-2\n"));
        }
        try (var store = open(dbPath)) {
            assertEquals(Optional.of(FIRST), store.findAsset("TOKEN"));
            store.disconnect(store.tip()); store.disconnect(store.tip());
        }
        try (var store = open(dbPath)) {
            assertTrue(store.findAsset("TOKEN").isEmpty()); assertEquals(0, store.tip().height());
        }
    }

    @Test void externalSharedUndoSurvivesAbruptProcessHalt() throws Exception {
        Path db = temp.resolve("hot"), undo = temp.resolve("cold");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CrashWriter.class.getName(), db.toString(), undo.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Asset crash writer timed out");
            assertEquals(23, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        try (var files = new RvnFlatFileStore(undo, "asset-test", 64 * 1024 * 1024);
             var store = new RvnCoinStore(db, id(0), files)) {
            assertEquals(id(1), store.tip().id());
            assertEquals(Optional.of(FIRST), store.findAsset("TOKEN"));
            assertEquals(Optional.of(coin(1000, 1)), store.find(point(1)));
            store.disconnect(store.tip());
            assertTrue(store.findAsset("TOKEN").isEmpty()); assertTrue(store.find(point(1)).isEmpty());
        }
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            var files = new RvnFlatFileStore(Path.of(args[1]), "asset-test", 64 * 1024 * 1024);
            var store = new RvnCoinStore(Path.of(args[0]), id(0), files);
            store.connect(store.tip(), id(1), changes(Map.of(), Map.of(point(1), coin(1000, 1))), delta(null, FIRST));
            Runtime.getRuntime().halt(23);
        }
    }
}
