package ru.bitcoin.node.rvn.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Mechanics use synthetic headers/deltas; no mainnet validity claim. */
class RvnTieredStorageTest {
    @TempDir Path temp;
    private static RvnHash256 id(int value) { byte[] bytes = new byte[32]; bytes[0] = (byte) value; return new RvnHash256(bytes); }
    private static OutPoint point() { return new OutPoint(new Hash256(id(1).wire()), new UInt32(0)); }
    private static RvnCoin coin() { return new RvnCoin(new TxOut(1000, new byte[]{0x51}), 1, false); }
    private static RvnCoinAccounting.Changes changes() { return new RvnCoinAccounting.Changes(0, Map.of(), Map.of(point(), coin())); }

    @Test
    void flatFilesRotateCheckIntegrityAndResumeAfterUnindexedTail() throws Exception {
        Path cold = temp.resolve("cold"); byte[] first, second, third;
        byte[] payload = new byte[400]; Arrays.fill(payload, (byte) 7);
        try (var files = new RvnFlatFileStore(cold, "test", 512, 1024)) {
            first = files.append(payload); second = files.append(payload); third = files.append(payload);
            assertArrayEquals(payload, files.read(first)); assertArrayEquals(payload, files.read(third));
            assertTrue(Files.exists(cold.resolve("segment-00000001.dat")));
            assertThrows(IOException.class, () -> new RvnFlatFileStore(cold, "test", 512, 1024));
        }
        // A torn/unindexed record tail cannot change the positions of committed records.
        Files.write(cold.resolve("segment-00000001.dat"), new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
        try (var files = new RvnFlatFileStore(cold, "test", 512, 1024)) {
            assertArrayEquals(payload, files.read(first)); assertArrayEquals(payload, files.read(second));
            assertArrayEquals(payload, files.read(third));
            assertArrayEquals(new byte[]{8, 9}, files.read(files.append(new byte[]{8, 9})));
        }
        byte[] data = Files.readAllBytes(cold.resolve("segment-00000000.dat")); data[40] ^= 1;
        Files.write(cold.resolve("segment-00000000.dat"), data);
        try (var files = new RvnFlatFileStore(cold, "test", 512, 1024)) {
            assertThrows(IOException.class, () -> files.read(first));
        }
    }

    @Test
    void archiveIdentityAndReferenceBoundsProtectForeignDataAndAllocation() throws Exception {
        Path cold = temp.resolve("cold"), foreign = temp.resolve("foreign");
        Files.createDirectories(foreign); Files.writeString(foreign.resolve("CURRENT"), "untouched");
        assertThrows(IOException.class, () -> new RvnFlatFileStore(foreign, "test", 512));
        assertEquals("untouched", Files.readString(foreign.resolve("CURRENT")));
        try (var files = new RvnFlatFileStore(cold, "test", 512)) {
            assertThrows(IOException.class, () -> new RvnFlatFileStore(cold, "other", 512));
            assertThrows(IOException.class, () -> files.append(new byte[0]));
            assertThrows(IOException.class, () -> files.append(new byte[513]));
            assertThrows(IOException.class, () -> files.read(new byte[47]));
            byte[] reference = files.append(new byte[]{1});
            java.nio.ByteBuffer.wrap(reference).putInt(12, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> files.read(reference));
        }
    }

    @Test
    void archiveBatchRotatesAndSurvivesReopenWithoutChangingRecordFormat() throws Exception {
        var payloads = List.of(new byte[400], new byte[400], new byte[400], new byte[]{7, 8});
        Path path = temp.resolve("batch-archive"); List<byte[]> references;
        try (var files = new RvnFlatFileStore(path, "batch", 512, 1024)) {
            references = files.appendBatch(payloads);
            assertThrows(IOException.class, () -> files.appendBatch(List.of(new byte[]{1}, new byte[513])));
        }
        try (var files = new RvnFlatFileStore(path, "batch", 512, 1024)) {
            for (int i = 0; i < references.size(); i++) assertArrayEquals(payloads.get(i), files.read(references.get(i)));
            assertArrayEquals(new byte[]{9}, files.read(files.append(new byte[]{9})));
        }
    }

    @Test
    void bodyBatchChecksEverythingBeforePublishingAndAdvancesContiguousCursorAtomically() throws Exception {
        Path hot = temp.resolve("batch-headers"), cold = temp.resolve("batch-blocks");
        byte[] genesis = RvnHeaderStoreTest.header(null, 1000, RvnHeaderStoreTest.BITS);
        var genesisId = RvnHeaderStoreTest.id(genesis); var parent = genesisId;
        var headers = new ArrayList<byte[]>(); var bodies = new LinkedHashMap<RvnHash256, byte[]>();
        for (int i = 1; i <= 4; i++) {
            byte[] raw = RvnHeaderStoreTest.header(parent, 1000 + 60 * i, RvnHeaderStoreTest.BITS);
            headers.add(raw); parent = RvnHeaderStoreTest.id(raw); bodies.put(parent, Arrays.copyOf(raw, 81));
        }
        var ids = new ArrayList<>(bodies.keySet());
        try (var files = new RvnFlatFileStore(cold, "batch-bodies", 8_000_000);
             var store = new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
            store.accept(headers, (raw, view) -> RvnHeaderStoreTest.id(raw));
            var firstBatch = new LinkedHashMap<RvnHash256, byte[]>();
            for (int i : new int[]{2, 0, 1}) firstBatch.put(ids.get(i), bodies.get(ids.get(i)));
            assertEquals(3, store.saveDownloadedBodies(firstBatch, (bytes, known) -> {}));
            assertEquals(3, store.downloadedBodyTip().height());
            assertEquals(0, store.saveDownloadedBodies(firstBatch, (bytes, known) -> {}));
            var conflict = bodies.get(ids.get(1)).clone(); conflict[80] = 1;
            var badBatch = new LinkedHashMap<RvnHash256, byte[]>();
            badBatch.put(ids.get(3), bodies.get(ids.get(3))); badBatch.put(ids.get(1), conflict);
            assertThrows(SecurityException.class, () -> store.saveDownloadedBodies(badBatch, (bytes, known) -> {}));
            assertFalse(store.hasBlockBody(ids.get(3))); assertEquals(3, store.downloadedBodyTip().height());
        }
        try (var files = new RvnFlatFileStore(cold, "batch-bodies", 8_000_000);
             var store = new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
            assertEquals(3, store.downloadedBodyTip().height());
            for (int i = 0; i < 3; i++) assertArrayEquals(bodies.get(ids.get(i)), store.blockBody(ids.get(i)));
            assertNull(store.blockBody(ids.get(3)));
        }
    }

    @Test
    void bodiesAreColdAndSsdContainsOnlyReferencesWithRestartAndMissingFileRejection() throws Exception {
        Path hot = temp.resolve("hot/headers"), cold = temp.resolve("cold/blocks");
        byte[] genesis = RvnHeaderStoreTest.header(null, 1000, RvnHeaderStoreTest.BITS);
        var genesisId = RvnHeaderStoreTest.id(genesis);
        byte[] header = RvnHeaderStoreTest.header(genesisId, 1060, RvnHeaderStoreTest.BITS);
        var blockId = RvnHeaderStoreTest.id(header); byte[] body = Arrays.copyOf(header, 81);
        try (var files = new RvnFlatFileStore(cold, "blocks", 8_000_000);
             var store = new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
            store.accept(List.of(header), (raw, view) -> RvnHeaderStoreTest.id(raw));
            assertTrue(store.saveDownloadedBody(blockId, body, (bytes, known) -> {}));
            assertArrayEquals(body, store.blockBody(blockId));
        }
        byte[] key = new byte[33]; System.arraycopy(blockId.wire(), 0, key, 1, 32);
        try (var options = new Options(); var db = RocksDB.openReadOnly(options, hot.toString())) {
            key[0] = 2; assertNull(db.get(key));
            key[0] = 4; assertEquals(48, db.get(key).length);
        }
        assertThrows(IOException.class, () -> new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT));
        try (var files = new RvnFlatFileStore(cold, "blocks", 8_000_000);
             var store = new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
            assertArrayEquals(body, store.blockBody(blockId)); assertEquals(1, store.downloadedBodyTip().height());
        }
        Files.delete(cold.resolve("segment-00000000.dat"));
        try (var files = new RvnFlatFileStore(cold, "blocks", 8_000_000);
             var store = new RvnHeaderStore(hot, genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
            assertThrows(IOException.class, () -> store.blockBody(blockId));
        }
    }

    @Test
    void undoIsColdWhileCoinsAndTipRemainAtomicOnSsd() throws Exception {
        Path hot = temp.resolve("hot/coins"), cold = temp.resolve("cold/undo");
        try (var files = new RvnFlatFileStore(cold, "undo", 64 * 1024 * 1024);
             var coins = new RvnCoinStore(hot, id(0), files)) {
            coins.connect(coins.tip(), id(1), changes()); assertEquals(Optional.of(coin()), coins.find(point()));
        }
        byte[] key = new byte[33]; key[0] = 3; key[1] = 1;
        try (var options = new Options(); var db = RocksDB.openReadOnly(options, hot.toString())) {
            assertEquals(48, db.get(key).length);
        }
        assertThrows(IOException.class, () -> new RvnCoinStore(hot, id(0)));
        try (var files = new RvnFlatFileStore(cold, "undo", 64 * 1024 * 1024);
             var coins = new RvnCoinStore(hot, id(0), files)) {
            coins.disconnect(coins.tip()); assertEquals(0, coins.tip().height()); assertTrue(coins.find(point()).isEmpty());
            coins.connect(coins.tip(), id(1), changes());
        }
        Files.delete(cold.resolve("segment-00000000.dat"));
        try (var files = new RvnFlatFileStore(cold, "undo", 64 * 1024 * 1024)) {
            assertThrows(IOException.class, () -> new RvnCoinStore(hot, id(0), files));
        }
    }

    @Test
    void crashBeforeAndAfterIndexCommitNeverProducesPartialCoinState() throws Exception {
        for (String mode : List.of("before", "after")) {
            Path root = temp.resolve(mode);
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, CrashWriter.class.getName(), root.toString(), mode).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(19, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            try (var files = new RvnFlatFileStore(root.resolve("cold"), "crash", 64 * 1024 * 1024);
                 var coins = new RvnCoinStore(root.resolve("hot"), id(0), files)) {
                assertEquals(mode.equals("after") ? 1 : 0, coins.tip().height());
                assertEquals(mode.equals("after"), coins.find(point()).isPresent());
                if (mode.equals("after")) { coins.disconnect(coins.tip()); assertTrue(coins.find(point()).isEmpty()); }
                else coins.connect(coins.tip(), id(1), changes());
            }
        }
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            var files = new RvnFlatFileStore(root.resolve("cold"), "crash", 64 * 1024 * 1024);
            var coins = new RvnCoinStore(root.resolve("hot"), id(0), files);
            if (args[1].equals("before")) files.append(new byte[]{1, 2, 3});
            else coins.connect(coins.tip(), id(1), changes());
            Runtime.getRuntime().halt(19);
        }
    }

    @Test
    void bodyGroupCommitSurvivesProcessCrashBeforeAndAfterIndexPublication() throws Exception {
        byte[] genesis = RvnHeaderStoreTest.header(null, 1000, RvnHeaderStoreTest.BITS);
        var genesisId = RvnHeaderStoreTest.id(genesis);
        for (String mode : List.of("before", "after")) {
            Path root = temp.resolve("body-crash-" + mode);
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, BodyCrashWriter.class.getName(), root.toString(), mode).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(19, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            try (var files = new RvnFlatFileStore(root.resolve("cold"), "body-crash", 8_000_000);
                 var store = new RvnHeaderStore(root.resolve("hot"), genesisId, genesis, RvnHeaderStoreTest.LIMIT, files)) {
                assertEquals(2, store.best().height());
                assertEquals(mode.equals("after") ? 2 : 0, store.downloadedBodyTip().height());
                for (int height = 1; height <= 2; height++) {
                    var header = store.ancestor(store.best(), height);
                    assertEquals(mode.equals("after"), store.hasBlockBody(header.id()));
                    if (mode.equals("after")) assertArrayEquals(Arrays.copyOf(header.raw(), 81), store.blockBody(header.id()));
                }
            }
        }
    }

    public static class BodyCrashWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            byte[] genesis = RvnHeaderStoreTest.header(null, 1000, RvnHeaderStoreTest.BITS);
            var genesisId = RvnHeaderStoreTest.id(genesis);
            var files = new RvnFlatFileStore(root.resolve("cold"), "body-crash", 8_000_000);
            var store = new RvnHeaderStore(root.resolve("hot"), genesisId, genesis, RvnHeaderStoreTest.LIMIT, files);
            var headers = new ArrayList<byte[]>(); var bodies = new LinkedHashMap<RvnHash256, byte[]>();
            var parent = genesisId;
            for (int height = 1; height <= 2; height++) {
                byte[] raw = RvnHeaderStoreTest.header(parent, 1000 + 60 * height, RvnHeaderStoreTest.BITS);
                parent = RvnHeaderStoreTest.id(raw); headers.add(raw); bodies.put(parent, Arrays.copyOf(raw, 81));
            }
            store.accept(headers, (raw, view) -> RvnHeaderStoreTest.id(raw));
            if (args[1].equals("before")) files.appendBatch(new ArrayList<>(bodies.values()));
            else store.saveDownloadedBodies(bodies, (bytes, known) -> {});
            Runtime.getRuntime().halt(19);
        }
    }
}
