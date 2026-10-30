package ru.bitcoin.node.rvn.app.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.consensus.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.*;

class RvnGpuHeaderVerifierTest {
    @TempDir Path temp;

    @Test void wireConversionMatchesIndependentCpuAndEveryMixIsChecked() throws Exception {
        var cpu = new RvnKawpowLightEngine(1024);
        var raws = new ArrayList<byte[]>(); var expected = new ArrayList<RvnPowResult>();
        for (int i = 0; i < 4; i++) {
            byte[] raw = new byte[120]; RvnKawpowWords.writeLE(1588788000 + i, raw, 68);
            RvnKawpowWords.writeLE(300 + i, raw, 76); RvnKawpowWords.writeLE(i, raw, 80);
            var calculated = cpu.calculateKawpow(raw); System.arraycopy(calculated.mixHashWire(), 0, raw, 88, 32);
            raws.add(raw); expected.add(calculated.result());
        }
        var independent = new RvnKawpowLightEngine(1024);
        try (var verifier = new RvnGpuHeaderVerifier(jobs -> {
            var hashes = new ArrayList<RvnCudaKawpowWorker.Hash>();
            for (var job : jobs) {
                var computed = independent.compute(job.height(), job.headerHash(), job.nonce());
                hashes.add(new RvnCudaKawpowWorker.Hash(computed.mixHash(), computed.finalHash()));
            }
            return hashes;
        })) {
            verifier.ready(null); var actual = verifier.calculate(raws, 300);
            for (int i = 0; i < raws.size(); i++) assertArrayEquals(expected.get(i).powDigestWire(), actual.get(i).powDigestWire());
            raws.get(2)[88] ^= 1;
            assertThrows(SecurityException.class, () -> verifier.calculate(raws, 300));
            assertThrows(SecurityException.class, () -> verifier.calculate(raws, 301));
        }
    }

    @Test void shutdownBeforeReadinessUnblocksCallerWithoutUsingGpu() throws Exception {
        var called = new AtomicBoolean(); var failure = new AtomicReference<Throwable>();
        var verifier = new RvnGpuHeaderVerifier(jobs -> { called.set(true); return List.of(); });
        var caller = Thread.ofPlatform().daemon(true).start(() -> {
            try { verifier.calculate(List.of(new byte[120]), 1); } catch (Throwable e) { failure.set(e); }
        });
        verifier.close(); caller.join(3000);
        assertFalse(caller.isAlive()); assertInstanceOf(InterruptedIOException.class, failure.get()); assertFalse(called.get());
    }

    @Test void gpuComputedHashStillMustMeetTargetBeforeAnyHeaderCommit() throws Exception {
        byte[] raw = Arrays.copyOf(child(RvnMainnetExpectedBits.GENESIS_ID, 1588788000), 120);
        RvnKawpowWords.writeLE(1, raw, 76);
        try (var verifier = new RvnGpuHeaderVerifier(jobs -> {
            byte[] hash = new byte[32]; Arrays.fill(hash, (byte) 0xff);
            return List.of(new RvnCudaKawpowWorker.Hash(new byte[32], hash));
        }); var pool = new RvnHeaderPowBatch(1, () -> TEST_POW, verifier); var store = open(temp.resolve("gpu"))) {
            verifier.ready(null);
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1588788010, pool);
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(raw))));
            assertEquals(0, store.best().height()); assertEquals(1, store.count());
        }
    }

    @Test void localGpuFailureStopsVerificationRatherThanSilentlyFallingBack() throws Exception {
        var cpuUsed = new AtomicBoolean();
        try (var pool = new RvnHeaderPowBatch(1, () -> { cpuUsed.set(true); return TEST_POW; },
                (raws, height) -> { throw new IOException("GPU hardware failure"); })) {
            assertThrows(UncheckedIOException.class, () -> pool.calculate(List.of(new byte[120]), 300));
            assertFalse(cpuUsed.get());
            assertEquals(1, pool.calculate(List.of(new byte[80]), 1).size()); assertTrue(cpuUsed.get());
        }
    }
}
