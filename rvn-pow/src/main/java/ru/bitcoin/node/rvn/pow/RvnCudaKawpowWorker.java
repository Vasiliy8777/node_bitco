package ru.bitcoin.node.rvn.pow;

import ru.bitcoin.node.common.NoncePermutation;

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
    private final RvnKawpowLightEngine verifier;
    private final java.security.SecureRandom auditRandom = new java.security.SecureRandom();
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
    public record Work(long height, byte[] headerHash, long nonce) {
        public Work {
            if (height < 0 || height > Integer.MAX_VALUE || headerHash == null || headerHash.length != 32)
                throw new IllegalArgumentException("CUDA verification job");
            headerHash = headerHash.clone();
        }
        @Override public byte[] headerHash() { return headerHash.clone(); }
    }
    public record Hash(byte[] mix, byte[] hash) {
        public Hash {
            if (mix == null || hash == null || mix.length != 32 || hash.length != 32) throw new IllegalArgumentException("CUDA verification hash size");
            mix = mix.clone(); hash = hash.clone();
        }
        @Override public byte[] mix() { return mix.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }

    /** Computes every input on GPU. Audit every result in small diagnostic
     * batches; in large batches independently recompute endpoints and two
     * unpredictable samples per epoch group. Caller still checks every mix/target. */
    public synchronized List<Hash> hashBatch(List<Work> supplied) throws IOException {
        var jobs = List.copyOf(supplied);
        if (jobs.isEmpty() || jobs.size() > 2000) throw new IllegalArgumentException("CUDA verification batch bounds");
        ensureOpen(); var result = new ArrayList<Hash>(jobs.size());
        for (int first = 0; first < jobs.size();) {
            int end = first + 1;
            long requested = RvnKawpowEpoch.fromHeight(jobs.get(first).height());
            while (end < jobs.size() && RvnKawpowEpoch.fromHeight(jobs.get(end).height()) == requested) end++;
            var group = jobs.subList(first, end); prepare(group.getFirst().height());
            var hashes = exchange(() -> {
                output.writeByte(3); output.writeInt(group.size());
                for (var job : group) { output.writeInt((int) job.height()); output.writeLong(job.nonce()); output.write(job.headerHash()); }
                output.flush(); status();
                if (input.readInt() != group.size()) throw new IOException("CUDA verification response count mismatch");
                var computed = new ArrayList<Hash>(group.size());
                for (int i = 0; i < group.size(); i++) computed.add(new Hash(exact(32), exact(32)));
                return computed;
            }, Duration.ofSeconds(30));
            var audit = new HashSet<Integer>();
            if (group.size() <= 8) { for (int i = 0; i < group.size(); i++) audit.add(i); }
            else {
                audit.add(0); audit.add(group.size() - 1);
                while (audit.size() < 4) audit.add(auditRandom.nextInt(group.size()));
            }
            try {
                for (int index : audit) {
                    ensureOpen();
                    var job = group.get(index); var expected = verifier.compute(job.height(), job.headerHash(), job.nonce());
                    var actual = hashes.get(index);
                    if (!Arrays.equals(expected.mixHash(), actual.mix()) || !Arrays.equals(expected.finalHash(), actual.hash()))
                        throw new IOException("CUDA header batch failed independent CPU audit at index " + (first + index));
                }
            } catch (IOException | RuntimeException e) { close(); throw e; }
            result.addAll(hashes); first = end;
        }
        ensureOpen();
        return List.copyOf(result);
    }

    public RvnCudaKawpowWorker(String python, Path worker, Path temporary, int device, long reserve) throws IOException {
        this(python, worker, temporary, device, reserve, 65536);
    }
    public RvnCudaKawpowWorker(String python, Path worker, Path temporary, int device, long reserve, int cacheItems) throws IOException {
        if (device < 0 || reserve < 256L * 1024 * 1024) throw new IllegalArgumentException("CUDA device/reserve");
        if (!Files.isRegularFile(worker)) throw new IOException("CUDA worker script not found: " + worker);
        this.temporary = temporary.toAbsolutePath(); this.device = device; this.reserve = reserve;
        verifier = new RvnKawpowLightEngine(cacheItems);
        Files.createDirectories(this.temporary);
        process = new ProcessBuilder(python, "-u", worker.toAbsolutePath().toString(), "--binary")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
    }

    public synchronized Batch search(long height, byte[] headerHash, long start, int count, BigInteger target) throws IOException {
        return search(height, headerHash, start, count, target, false);
    }

    /** start/count identify logical counters; returned candidates contain actual permuted nonces. */
    public synchronized Batch searchPermuted(long height, byte[] headerHash, long start, int count, BigInteger target) throws IOException {
        return search(height, headerHash, start, count, target, true);
    }

    private Batch search(long height, byte[] headerHash, long start, int count, BigInteger target, boolean permuted) throws IOException {
        if (height < 0 || height > Integer.MAX_VALUE || headerHash == null || headerHash.length != 32
                || count < 1 || count > 65536 || target == null || target.signum() < 0 || target.bitLength() > 256
                || Long.compareUnsigned(start + count - 1L, start) < 0)
            throw new IllegalArgumentException("Invalid CUDA KawPoW search");
        ensureOpen();
        prepare(height);
        byte[] header = headerHash.clone();
        long key = NoncePermutation.key64(header);
        byte[] targetBytes = new byte[32], raw = target.toByteArray();
        System.arraycopy(raw, Math.max(0, raw.length - 32), targetBytes, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        var batch = exchange(() -> {
            output.writeByte(permuted ? 4 : 2); output.writeInt((int) height); output.writeLong(start); output.writeInt(count);
            output.write(header); output.write(targetBytes); output.flush(); status();
            boolean overflow = input.readBoolean(); int size = input.readInt();
            if (size < 0 || size > 128 || size > count) throw new IOException("Invalid CUDA result count");
            var candidates = new ArrayList<Candidate>(); var seen = new HashSet<Long>();
            for (int i = 0; i < size; i++) {
                long nonce = input.readLong(); byte[] mix = exact(32), hash = exact(32);
                long counter = permuted ? NoncePermutation.inverse64(nonce, key) : nonce;
                if (Long.compareUnsigned(counter - start, count) >= 0 || !seen.add(nonce))
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
            verifier.prepareEpoch(requested, cache);
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
