package ru.bitcoin.node.rvn.pow;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Local CUDA subprocess with bounded searches. Every candidate is independently
 * checked with the Java light verifier. It accepts hashes, not network jobs or RPC.
 * Close/cancel destroys the process and its CUDA context, including an active batch. */
public final class RvnCudaKawpowWorker implements AutoCloseable {
    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "rvn-cuda-protocol"); thread.setDaemon(true); return thread;
    });
    private final Path temporary;
    private final int device;
    private final long reserve;
    private final RvnKawpowLightEngine verifier = new RvnKawpowLightEngine(1024);
    private long epoch = -1;
    private volatile boolean closed;

    public record Candidate(long nonce, byte[] mix, byte[] hash) {
        public Candidate { mix = mix.clone(); hash = hash.clone(); }
        @Override public byte[] mix() { return mix.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
    public record Batch(List<Candidate> candidates, boolean overflow, int tested) {
        public Batch { candidates = List.copyOf(candidates); }
    }

    public RvnCudaKawpowWorker(String python, Path worker, Path temporary, int device, long reserve) throws IOException {
        if (device < 0 || reserve < 256L * 1024 * 1024) throw new IllegalArgumentException("CUDA device/reserve");
        if (!Files.isRegularFile(worker)) throw new IOException("CUDA worker script not found: " + worker);
        this.temporary = temporary.toAbsolutePath(); this.device = device; this.reserve = reserve;
        Files.createDirectories(this.temporary);
        process = new ProcessBuilder(python, "-u", worker.toAbsolutePath().toString(), "--binary")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
    }

    public synchronized Batch search(long height, byte[] headerHash, long start, int count, BigInteger target) throws IOException {
        if (height < 0 || height > Integer.MAX_VALUE || headerHash == null || headerHash.length != 32
                || count < 1 || count > 65536 || target == null || target.signum() < 0 || target.bitLength() > 256
                || Long.compareUnsigned(start + count - 1L, start) < 0)
            throw new IllegalArgumentException("Invalid CUDA KawPoW search");
        ensureOpen();
        prepare(height);
        byte[] header = headerHash.clone();
        byte[] targetBytes = new byte[32], raw = target.toByteArray();
        System.arraycopy(raw, Math.max(0, raw.length - 32), targetBytes, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        var batch = exchange(() -> {
            output.writeByte(2); output.writeInt((int) height); output.writeLong(start); output.writeInt(count);
            output.write(header); output.write(targetBytes); output.flush(); status();
            boolean overflow = input.readBoolean(); int size = input.readInt();
            if (size < 0 || size > 128 || size > count) throw new IOException("Invalid CUDA result count");
            var candidates = new ArrayList<Candidate>(); var seen = new HashSet<Long>();
            for (int i = 0; i < size; i++) {
                long nonce = input.readLong(); byte[] mix = exact(32), hash = exact(32);
                if (Long.compareUnsigned(nonce - start, count) >= 0 || !seen.add(nonce))
                    throw new IOException("CUDA returned duplicate/out-of-range nonce");
                candidates.add(new Candidate(nonce, mix, hash));
            }
            return new Batch(candidates, overflow, count);
        }, Duration.ofSeconds(30));
        try {
            for (var candidate : batch.candidates()) {
                ensureOpen();
                var expected = verifier.compute(height, header, candidate.nonce());
                if (!Arrays.equals(expected.mixHash(), candidate.mix()) || !Arrays.equals(expected.finalHash(), candidate.hash())
                        || new BigInteger(1, candidate.hash()).compareTo(target) > 0)
                    throw new IOException("CUDA KawPoW candidate failed independent Java verification");
            }
            ensureOpen();
            return batch;
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    private void prepare(long height) throws IOException {
        long requested = RvnKawpowEpoch.fromHeight(height);
        if (epoch == requested) return;
        Path cacheFile = Files.createTempFile(temporary, "rvn-cuda-light-", ".bin");
        try {
            var cache = RvnKawpowCache.forEpoch(requested);
            try (var file = new BufferedOutputStream(Files.newOutputStream(cacheFile))) {
                for (int i = 0; i < cache.size(); i++) file.write(cache.rowInternal(i));
            }
            byte[] path = cacheFile.toString().getBytes(StandardCharsets.UTF_8);
            long bytes = exchange(() -> {
                output.writeByte(1); output.writeInt((int) height); output.writeInt(device); output.writeLong(reserve);
                output.writeInt(path.length); output.write(path); output.flush(); status();
                return input.readLong();
            }, Duration.ofMinutes(10));
            if (bytes != RvnKawpowDatasetSizes.datasetBytes(requested) / 256 * 256) {
                close(); throw new IOException("CUDA DAG size mismatch");
            }
            epoch = requested;
        } finally { Files.deleteIfExists(cacheFile); }
    }

    private byte[] exact(int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("CUDA worker closed its output");
        return bytes;
    }
    private void status() throws IOException {
        int status = input.readInt();
        if (status == 0) return;
        if (status != -1) throw new IOException("Invalid CUDA response status");
        int length = input.readInt();
        if (length < 0 || length > 8192) throw new IOException("Invalid CUDA error length");
        throw new IOException("CUDA worker: " + new String(exact(length), StandardCharsets.UTF_8));
    }
    private <T> T exchange(Callable<T> action, Duration timeout) throws IOException {
        ensureOpen();
        Future<T> future = io.submit(action);
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); close(); throw new InterruptedIOException("CUDA request interrupted");
        } catch (ExecutionException | TimeoutException e) {
            close(); throw new IOException("CUDA request failed or timed out", e.getCause() == null ? e : e.getCause());
        }
    }
    private void ensureOpen() throws IOException {
        if (closed || !process.isAlive()) throw new IOException("CUDA worker closed or exited");
    }
    @Override public void close() {
        closed = true;
        process.destroyForcibly(); io.shutdownNow();
    }
}
