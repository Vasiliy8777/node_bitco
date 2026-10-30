package ru.bitcoin.node.rvn.app.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.*;

class RvnHeaderPowBatchTest {
    @TempDir Path temp;
    private static List<byte[]> headers(int count) {
        var result = new ArrayList<byte[]>();
        for (int i = 0; i < count; i++) { byte[] raw = new byte[80]; raw[0] = (byte) i; result.add(raw); }
        return result;
    }

    @Test void boundedWorkersComputeConcurrentlyButReturnOriginalOrder() throws Exception {
        var started = new CountDownLatch(4); var release = new CountDownLatch(1);
        var created = new AtomicInteger(); var results = new AtomicReference<List<RvnPowResult>>();
        var error = new AtomicReference<Throwable>();
        try (var pool = new RvnHeaderPowBatch(4, () -> {
            created.incrementAndGet();
            return (algorithm, raw) -> {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new RvnPowException("Test worker gate timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RvnPowException("Test interrupted", e); }
                return new RvnPowResult(RvnWire.doubleSha(raw), new byte[32]);
            };
        })) {
            var raws = headers(40);
            var caller = Thread.ofPlatform().daemon(true).start(() -> {
                try { results.set(pool.calculate(raws, 1)); } catch (Throwable e) { error.set(e); }
            });
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(4, created.get()); }
            finally { release.countDown(); }
            caller.join(5000); assertFalse(caller.isAlive()); if (error.get() != null) fail(error.get());
            assertEquals(raws.size(), results.get().size());
            for (int i = 0; i < raws.size(); i++) assertArrayEquals(RvnWire.doubleSha(raws.get(i)), results.get().get(i).blockIdWire());
            assertEquals(4, created.get());
            assertThrows(IllegalArgumentException.class, () -> pool.calculate(headers(2001), 1));
        }
    }

    @Test void closeCancelsQueuedAndRunningWorkWithoutHangingCaller() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var pool = new RvnHeaderPowBatch(1, () -> (algorithm, raw) -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RvnPowException("Stopped", e); }
            return new RvnPowResult(RvnWire.doubleSha(raw), new byte[32]);
        });
        var caller = Thread.ofPlatform().daemon(true).start(() -> {
            try { pool.calculate(headers(100), 1); } catch (Throwable e) { failure.set(e); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS)); pool.close();
            caller.join(5000); assertFalse(caller.isAlive()); assertInstanceOf(IOException.class, failure.get());
            assertThrows(IOException.class, () -> pool.calculate(headers(1), 1));
        } finally { release.countDown(); pool.close(); caller.interrupt(); }
    }

    @Test void callerInterruptionCancelsBatchAndPoolRemainsUsable() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>(); var interrupted = new AtomicBoolean();
        try (var pool = new RvnHeaderPowBatch(2, () -> (algorithm, raw) -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RvnPowException("Stopped", e); }
            return new RvnPowResult(RvnWire.doubleSha(raw), new byte[32]);
        })) {
            var caller = Thread.ofPlatform().daemon(true).start(() -> {
                try { pool.calculate(headers(100), 1); }
                catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS)); caller.interrupt(); caller.join(5000);
                assertFalse(caller.isAlive()); assertInstanceOf(InterruptedIOException.class, failure.get()); assertTrue(interrupted.get());
            } finally { release.countDown(); caller.interrupt(); }
            assertEquals(1, pool.calculate(headers(1), 1).size());
        }
    }

    @Test void invalidProofLeavesWholeHeaderBatchUncommittedAndCanRetry() throws Exception {
        var bad = new AtomicBoolean(true);
        RvnPowEngine engine = (algorithm, raw) -> {
            byte[] digest = new byte[32];
            if (bad.get() && raw[0] == 5) Arrays.fill(digest, (byte) 0xff);
            return new RvnPowResult(RvnWire.doubleSha(raw), digest);
        };
        try (var pool = new RvnHeaderPowBatch(4, () -> engine); var store = open(temp.resolve("headers"))) {
            byte[] first = child(ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits.GENESIS_ID, 1514999554L);
            byte[] second = child(new ru.bitcoin.node.rvn.consensus.RvnHash256(RvnWire.doubleSha(first)), 1514999614L); second[0] = 5;
            var chain = new RvnMainnetHeaderChain(store, engine, () -> 1515000000L, pool);
            var input = List.of(parsed(first), parsed(second));
            assertThrows(SecurityException.class, () -> chain.accept(input));
            assertEquals(0, store.best().height()); assertEquals(1, store.count());
            bad.set(false); assertEquals(2, chain.accept(input).added()); assertEquals(2, store.best().height());
        }
    }

    @Test void realIndependentLightEnginesMatchSerialResultsAndRejectBadMix() throws Exception {
        var serial = new RvnKawpowLightEngine(16); RvnKawpowSelfTest.requireValid(serial);
        var raws = new ArrayList<byte[]>(); var expected = new ArrayList<RvnPowResult>();
        for (int i = 0; i < 8; i++) {
            byte[] raw = new byte[120]; RvnKawpowWords.writeLE(1588788000 + i, raw, 68);
            RvnKawpowWords.writeLE(300 + i, raw, 76); RvnKawpowWords.writeLE(i, raw, 80);
            var computed = serial.calculateKawpow(raw);
            System.arraycopy(computed.mixHashWire(), 0, raw, 88, 32);
            raws.add(raw); expected.add(computed.result());
        }
        try (var pool = new RvnHeaderPowBatch(4, () -> {
            var light = new RvnKawpowLightEngine(16); RvnKawpowSelfTest.requireValid(light);
            return new RvnJavaPowEngine(light);
        })) {
            var actual = pool.calculate(raws, 300);
            for (int i = 0; i < raws.size(); i++) {
                assertArrayEquals(expected.get(i).blockIdWire(), actual.get(i).blockIdWire());
                assertArrayEquals(expected.get(i).powDigestWire(), actual.get(i).powDigestWire());
            }
            raws.get(3)[88] ^= 1;
            assertThrows(SecurityException.class, () -> pool.calculate(raws, 300));
        }
    }
}
