package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Core 32.1 mempool estimator: six-block coverage, weight percentiles and tip-bound seven-second cache.
 * Adapted from policy/fees/mempool_estimator.cpp, copyright the Bitcoin Core developers.
 * MIT license: META-INF/licenses/bitcoin-core-MIT.txt.
 */
public final class MempoolFeeEstimator {
    public record BlockStats(long height, long mempoolWeight, long blockWeight) {
        public BlockStats {
            if (height < 0 || mempoolWeight < 0 || blockWeight < 0 || blockWeight > 4_000_000)
                throw new IllegalArgumentException("Invalid mined block statistics");
        }
    }
    public record FeeChunk(long fee, long virtualSize) {
        public FeeChunk {
            if (virtualSize <= 0) throw new IllegalArgumentException("Invalid fee chunk size");
        }
    }
    public record Estimate(Long satoshisPerKvB, String error) { }
    private final List<BlockStats> blocks = new ArrayList<>();
    private Hash256 trackedTip;
    private Hash256 cachedTip;
    private long cachedAt;
    private long conservativeRate, economicalRate;

    public synchronized void connected(BlockStats stats, Hash256 tip) {
        blocks.removeIf(block -> block.height() >= stats.height());
        if (!blocks.isEmpty() && blocks.getLast().height() + 1 != stats.height()) blocks.clear();
        if (blocks.size() == 6) blocks.removeFirst();
        blocks.add(stats); trackedTip = tip; cachedTip = null;
    }

    public synchronized void rewind(long height, Hash256 tip) {
        blocks.removeIf(block -> block.height() > height);
        trackedTip = tip; cachedTip = null;
    }

    public synchronized List<BlockStats> statistics() { return List.copyOf(blocks); }

    public synchronized String healthError() {
        if (blocks.size() < 6) return "mempool_policy: Not enough recent block data for fee rate estimation";
        long weight = blocks.stream().mapToLong(BlockStats::blockWeight).sum();
        long represented = blocks.stream().mapToLong(BlockStats::mempoolWeight).sum();
        if (weight >= 4_000_000 && represented * 4 < weight * 3)
            return "mempool_policy: Mempool is unreliable for fee rate estimation";
        return null;
    }

    public synchronized Estimate estimate(Hash256 tip, long now, boolean conservative, long floor,
                                           java.util.function.Supplier<List<FeeChunk>> templateChunks) {
        String error = healthError();
        if (error != null) return new Estimate(null, error);
        if (!tip.equals(cachedTip) || now < cachedAt || now - cachedAt > 7_000_000_000L) {
            var chunks = new ArrayList<>(templateChunks.get());
            chunks.sort((left, right) -> java.math.BigInteger.valueOf(right.fee()).multiply(java.math.BigInteger.valueOf(left.virtualSize()))
                    .compareTo(java.math.BigInteger.valueOf(left.fee()).multiply(java.math.BigInteger.valueOf(right.virtualSize()))));
            long accumulated = 0;
            Long p50 = null, p75 = null;
            for (var chunk : chunks) {
                accumulated += chunk.virtualSize() * 4;
                long rate = Math.max(0, java.math.BigInteger.valueOf(chunk.fee()).multiply(java.math.BigInteger.valueOf(1000))
                        .divide(java.math.BigInteger.valueOf(chunk.virtualSize())).longValueExact());
                if (accumulated >= 2_000_000 && p50 == null) p50 = rate;
                if (accumulated >= 3_000_000) { p75 = rate; break; }
            }
            conservativeRate = p50 == null ? floor : p50;
            economicalRate = p75 == null ? floor : p75;
            cachedTip = tip; cachedAt = now;
        }
        return new Estimate(Math.max(floor, conservative ? conservativeRate : economicalRate), null);
    }

    /** Core's v1 disk format: LE version, CompactSize count, uint64 triples, wire hash. */
    public synchronized void save(Path path) throws IOException {
        if (trackedTip == null) return;
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(Integer.reverseBytes(1)); output.writeByte(blocks.size());
            for (var block : blocks) {
                output.writeLong(Long.reverseBytes(block.height()));
                output.writeLong(Long.reverseBytes(block.mempoolWeight()));
                output.writeLong(Long.reverseBytes(block.blockWeight()));
            }
            output.write(trackedTip.bytes());
        }
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    public synchronized boolean load(Path path, long height, Hash256 tip) {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != Integer.reverseBytes(1)) return false;
            int count = input.readUnsignedByte();
            if (count > 6) return false;
            var restored = new ArrayList<BlockStats>();
            for (int i = 0; i < count; i++) {
                var block = new BlockStats(Long.reverseBytes(input.readLong()), Long.reverseBytes(input.readLong()),
                        Long.reverseBytes(input.readLong()));
                if (i > 0 && restored.getLast().height() + 1 != block.height()) return false;
                restored.add(block);
            }
            byte[] hash = input.readNBytes(32);
            if (hash.length != 32 || !Arrays.equals(hash, tip.bytes()) || input.read() != -1
                    || (!restored.isEmpty() && restored.getLast().height() != height)) return false;
            blocks.clear(); blocks.addAll(restored); trackedTip = tip; cachedTip = null;
            return true;
        } catch (IOException | IllegalArgumentException e) { return false; }
    }
}
