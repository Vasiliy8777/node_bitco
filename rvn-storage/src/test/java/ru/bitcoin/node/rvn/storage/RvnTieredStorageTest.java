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
}
