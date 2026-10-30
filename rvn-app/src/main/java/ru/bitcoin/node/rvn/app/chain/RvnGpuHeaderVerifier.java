package ru.bitcoin.node.rvn.app.chain;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared mining CUDA context, used only after mandatory self-tests finish.
 * GPU computes every mix/hash; Java checks every embedded mix. Context and
 * target remain authoritative in ordered RvnMainnetHeaderChain admission. */
public final class RvnGpuHeaderVerifier implements RvnHeaderPowBatch.Gpu, AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RvnGpuHeaderVerifier.class);
    @FunctionalInterface interface Hasher { List<RvnCudaKawpowWorker.Hash> hash(List<RvnCudaKawpowWorker.Work> jobs) throws IOException; }
    private final Hasher hasher;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean closed;
    private long lastEpoch = -1;
    public RvnGpuHeaderVerifier(RvnCudaKawpowWorker worker) { this(worker::hashBatch); }
    RvnGpuHeaderVerifier(Hasher hasher) { this.hasher = Objects.requireNonNull(hasher); }
    @EventListener public void ready(ApplicationReadyEvent event) { ready.countDown(); }

    @Override public List<RvnPowResult> calculate(List<byte[]> raws, long firstHeight) throws IOException {
        if (raws.isEmpty() || raws.size() > 2000 || firstHeight < 0) throw new IllegalArgumentException("GPU header batch bounds");
        try {
            if (!ready.await(15, TimeUnit.MINUTES)) throw new IOException("CUDA startup tests have not completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("GPU header wait interrupted");
        }
        if (closed) throw new InterruptedIOException("GPU header verifier closed");
        var jobs = new ArrayList<RvnCudaKawpowWorker.Work>(raws.size());
        for (int i = 0; i < raws.size(); i++) {
            byte[] raw = raws.get(i);
            var header = RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME);
            long height = Math.addExact(firstHeight, i);
            if (!header.isKawpow() || header.declaredHeight() != height)
                throw new SecurityException("GPU KawPoW header height/format mismatch");
            byte[] hash = reverse(RvnWire.doubleSha(Arrays.copyOf(raw, 80)));
            jobs.add(new RvnCudaKawpowWorker.Work(height, hash, new RvnKawpowHeaderView(raw).nonceBits()));
        }
        long endEpoch = RvnKawpowEpoch.fromHeight(jobs.getLast().height());
        if (lastEpoch != endEpoch) log.info("RVN GPU header verification entering epoch {}..{}; preparing DAG if needed, headers={}",
                RvnKawpowEpoch.fromHeight(firstHeight), endEpoch, jobs.size());
        var hashes = hasher.hash(List.copyOf(jobs));
        if (hashes.size() != raws.size()) throw new IOException("GPU header result count mismatch");
        var results = new ArrayList<RvnPowResult>(hashes.size());
        for (int i = 0; i < hashes.size(); i++) {
            byte[] mixWire = reverse(hashes.get(i).mix());
            if (!Arrays.equals(mixWire, Arrays.copyOfRange(raws.get(i), 88, 120)))
                throw new SecurityException("Invalid GPU-checked KawPoW mix at " + (firstHeight + i));
            byte[] wire = reverse(hashes.get(i).hash());
            results.add(new RvnPowResult(wire, wire));
        }
        if (closed) throw new InterruptedIOException("GPU header verifier closed");
        lastEpoch = endEpoch;
        return List.copyOf(results);
    }
    private static byte[] reverse(byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) { byte b = bytes[i]; bytes[i] = bytes[bytes.length - 1 - i]; bytes[bytes.length - 1 - i] = b; }
        return bytes;
    }
    @Override public void close() { closed = true; ready.countDown(); }
}
