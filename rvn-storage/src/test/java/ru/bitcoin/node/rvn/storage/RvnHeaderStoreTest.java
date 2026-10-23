package ru.bitcoin.node.rvn.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.protocol.RvnWire;
import java.nio.file.*;
import java.math.BigInteger;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Storage mechanics use synthetic headers and a test-only identity validator, not consensus proofs. */
class RvnHeaderStoreTest {
    @TempDir Path temp;
    static final BigInteger LIMIT = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);
    static final long BITS = 0x207fffffL;
    static RvnHash256 id(byte[] raw) { return new RvnHash256(RvnWire.doubleSha(raw)); }
    static void put32(byte[] raw, int offset, long value) {
        for (int i = 0; i < 4; i++) raw[offset + i] = (byte) (value >>> (i * 8));
    }
    static byte[] header(RvnHash256 parent, long time, long bits) {
        byte[] raw = new byte[80]; raw[0] = 4;
        if (parent != null) System.arraycopy(parent.wire(), 0, raw, 4, 32);
        put32(raw, 68, time); put32(raw, 72, bits); return raw;
    }
    final byte[] genesis = header(null, 1000, BITS);
    RvnHeaderStore open(Path path) throws IOException { return new RvnHeaderStore(path, id(genesis), genesis, LIMIT); }

    @Test
    void durableRestartDuplicateAndRejectedBatch() throws Exception {
        Path path = temp.resolve("headers");
        byte[] first = header(id(genesis), 1060, BITS), second = header(id(first), 1120, BITS);
        try (var store = open(path)) {
            assertEquals(2, store.accept(List.of(first, second), (raw, view) -> id(raw)));
            assertEquals(2, store.best().height());
            assertEquals(3, store.count());
            assertEquals(0, store.accept(List.of(first, second), (raw, view) -> id(raw)));
            byte[] third = header(id(second), 1180, BITS), fourth = header(id(third), 1240, BITS);
            assertThrows(SecurityException.class, () -> store.accept(List.of(third, fourth), (raw, view) -> {
                if (Arrays.equals(raw, fourth)) throw new SecurityException("Test rejection");
                return id(raw);
            }));
            assertNull(store.find(id(third))); assertEquals(2, store.best().height());
        }
        try (var reopened = open(path)) {
            assertEquals(id(second), reopened.best().id()); assertEquals(3, reopened.count());
            assertArrayEquals(second, reopened.find(id(second)).raw());
        }
    }

    @Test
    void heavierForkLocatorsAndSkipLinksSurviveCacheEvictionAndRestart() throws Exception {
        Path path = temp.resolve("headers"); RvnHash256 tip = id(genesis);
        try (var store = open(path)) {
            for (int start = 1; start <= 9000; start += 1000) {
                List<byte[]> batch = new ArrayList<>();
                for (int height = start; height < start + 1000; height++) {
                    byte[] raw = header(tip, 1000 + height, BITS); tip = id(raw); batch.add(raw);
                }
                store.accept(batch, (raw, view) -> id(raw));
            }
            for (int height : new int[]{0, 1, 2, 100, 4095, 8191, 8999, 9000})
                assertEquals(height, store.ancestor(store.best(), height).height());
            assertEquals(tip, new RvnHash256(store.locators().getFirst()));
            assertEquals(id(genesis), new RvnHash256(store.locators().getLast()));
            assertTrue(store.locators().size() <= 101);
            // A shorter branch with more work must win; height alone never selects a tip.
            byte[] heavy = header(id(genesis), 20000, 0x1e00ffffL);
            store.accept(List.of(heavy), (raw, view) -> id(raw));
            assertEquals(id(heavy), store.best().id()); assertEquals(1, store.best().height());
            assertEquals(tip, new RvnHash256(store.locators(tip).getFirst()));
        }
        try (var reopened = open(path)) {
            assertEquals(1, reopened.best().height()); assertEquals(9002, reopened.count());
            assertEquals(8191, reopened.ancestor(reopened.require(tip), 8191).height());
        }
    }

    @Test
    void wrongDirectoryIdentityAndConcurrentOpenFailWithoutTouchingOtherFiles() throws Exception {
        Path foreign = temp.resolve("bitcoin"); Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("CURRENT"), "do not change");
        assertThrows(IOException.class, () -> open(foreign));
        assertEquals("do not change", Files.readString(foreign.resolve("CURRENT")));
        assertFalse(Files.exists(foreign.resolve("RVN_HEADERS")));
        Path owned = temp.resolve("rvn");
        try (var store = open(owned)) { assertThrows(IOException.class, () -> open(owned)); }
        byte[] other = genesis.clone(); other[0] = 5;
        assertThrows(IOException.class, () -> new RvnHeaderStore(owned, id(other), other, LIMIT));
        try (var reopened = open(owned)) { assertEquals(0, reopened.best().height()); }
    }

    @Test
    void invalidParentDiscontinuousBatchAndInterruptionCannotCommit() throws Exception {
        try (var store = open(temp.resolve("rvn"))) {
            byte[] first = header(id(genesis), 1060, BITS);
            byte[] sibling = header(id(genesis), 1120, BITS);
            assertThrows(SecurityException.class, () -> store.accept(List.of(first, sibling), (raw, view) -> id(raw)));
            assertEquals(1, store.count());
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class,
                    () -> store.accept(List.of(first), (raw, view) -> id(raw))); }
            finally { Thread.interrupted(); }
            assertEquals(1, store.count());
            byte[] unknown = header(id(first), 1180, BITS);
            assertThrows(SecurityException.class, () -> store.accept(List.of(unknown), (raw, view) -> id(raw)));
        }
    }

    @Test
    void missingCurrentCannotSilentlyResetAnExistingDatabase() throws Exception {
        Path path = temp.resolve("damaged");
        try (var store = open(path)) {
            store.accept(List.of(header(id(genesis), 1060, BITS)), (raw, view) -> id(raw));
        }
        Files.move(path.resolve("CURRENT"), path.resolve("CURRENT.saved"));
        assertThrows(IOException.class, () -> open(path));
        assertFalse(Files.exists(path.resolve("CURRENT")));
        Files.move(path.resolve("CURRENT.saved"), path.resolve("CURRENT"));
        try (var reopened = open(path)) { assertEquals(1, reopened.best().height()); }
    }

    @Test
    void downloadedBodiesHaveIndependentCursorRecoverOutOfOrderAndRewindOnReorg() throws Exception {
        Path path = temp.resolve("bodies");
        byte[] first = header(id(genesis), 1060, BITS), second = header(id(first), 1120, BITS);
        byte[] firstBody = Arrays.copyOf(first, 81), secondBody = Arrays.copyOf(second, 81);
        try (var store = open(path)) {
            store.accept(List.of(first, second), (raw, view) -> id(raw));
            assertEquals(id(first), store.nextMissingBlockBody().id());
            // Storage-only synthetic bodies; production passes real commitment checks here.
            assertTrue(store.saveDownloadedBody(id(second), secondBody, (raw, h) -> {}));
            assertEquals(0, store.downloadedBodyTip().height());
            assertTrue(store.saveDownloadedBody(id(first), firstBody, (raw, h) -> {}));
            assertNull(store.nextMissingBlockBody()); assertEquals(2, store.downloadedBodyTip().height());
        }
        try (var store = open(path)) {
            assertArrayEquals(secondBody, store.blockBody(id(second)));
            assertNull(store.nextMissingBlockBody());
            byte[] heavy = header(id(genesis), 1180, 0x1e00ffffL), body = Arrays.copyOf(heavy, 81);
            store.accept(List.of(heavy), (raw, view) -> id(raw));
            assertEquals(id(heavy), store.nextMissingBlockBody().id());
            assertEquals(0, store.downloadedBodyTip().height());
            assertThrows(SecurityException.class, () -> store.saveDownloadedBody(id(heavy), body,
                    (raw, h) -> { throw new SecurityException("Invalid candidate body"); }));
            assertFalse(store.hasBlockBody(id(heavy))); assertEquals(0, store.downloadedBodyTip().height());
            assertTrue(store.saveDownloadedBody(id(heavy), body, (raw, h) -> {}));
            assertFalse(store.saveDownloadedBody(id(heavy), body, (raw, h) -> {}));
            assertEquals(id(heavy), store.downloadedBodyTip().id());
            assertNotNull(store.blockBody(id(second))); // off-branch data is retained
        }
    }

    @Test
    void malformedDownloadedBodyMetadataStopsFurtherUse() throws Exception {
        Path path = temp.resolve("body-corruption");
        byte[] first = header(id(genesis), 1060, BITS), body = Arrays.copyOf(first, 81);
        try (var store = open(path)) {
            store.accept(List.of(first), (raw, view) -> id(raw));
            store.saveDownloadedBody(id(first), body, (raw, h) -> {});
        }
        try (var options = new org.rocksdb.Options(); var db = org.rocksdb.RocksDB.open(options, path.toString())) {
            byte[] key = new byte[33]; key[0] = 3; System.arraycopy(id(first).wire(), 0, key, 1, 32);
            db.put(key, new byte[]{1});
        }
        try (var store = open(path)) {
            assertThrows(IOException.class, store::downloadedBodyTip);
            assertThrows(IllegalStateException.class, store::best);
        }
    }
}
