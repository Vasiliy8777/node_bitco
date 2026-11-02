package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Confirmation statistics and smart-fee selection adapted from Bitcoin Core v32.1
 * policy/fees/block_policy_estimator.cpp, copyright the Bitcoin Core developers.
 * MIT license: META-INF/licenses/bitcoin-core-MIT.txt.
 * Unconfirmed ages are derived from the tracked set instead of Core's circular buffers.
 */
public final class BlockPolicyFeeEstimator {
    public record Estimate(Long satoshisPerKvB, int blocks) { }
    private record Tracked(long height, int bucket, long rate) { }
    private final double[] buckets;
    private final Horizon[] horizons;
    private final Map<Hash256, Tracked> tracked = new HashMap<>();
    private long bestHeight, firstHeight, historicalFirst, historicalBest;

    public BlockPolicyFeeEstimator() {
        var boundaries = new ArrayList<Double>();
        for (double fee = 100; fee <= 1e7; fee *= 1.05) boundaries.add(fee);
        boundaries.add(1e99);
        buckets = boundaries.stream().mapToDouble(Double::doubleValue).toArray();
        horizons = new Horizon[]{new Horizon(12, 1, .962, .5), new Horizon(24, 2, .9952, .1),
                new Horizon(42, 24, .99931, .1)};
    }

    public synchronized void added(Hash256 txid, long height, long fee, long vsize, boolean eligible) {
        if (!eligible || height != bestHeight || tracked.containsKey(txid)) return;
        if (fee < 0 || vsize <= 0) throw new IllegalArgumentException("Invalid fee sample");
        long rate = java.math.BigInteger.valueOf(fee).multiply(java.math.BigInteger.valueOf(1000))
                .divide(java.math.BigInteger.valueOf(vsize)).longValueExact();
        int bucket = Arrays.binarySearch(buckets, (double) rate);
        if (bucket < 0) bucket = -bucket - 1;
        tracked.put(txid, new Tracked(height, bucket, rate));
    }

    public synchronized void removed(Hash256 id) {
        var sample = tracked.remove(id);
        if (sample == null) return;
        long age = bestHeight == 0 ? 0 : bestHeight - sample.height();
        for (var horizon : horizons) {
            for (int period = 0; period < Math.min(horizon.periods, age / horizon.scale); period++)
                horizon.failed[period][sample.bucket()]++;
        }
    }

    public synchronized void connected(long height, Collection<Hash256> confirmed) {
        if (height <= bestHeight) return;
        bestHeight = height;
        for (var horizon : horizons) horizon.decay();
        int recorded = 0;
        for (var id : confirmed) {
            var sample = tracked.remove(id);
            if (sample == null || height <= sample.height()) continue;
            for (var horizon : horizons) {
                long periods = (height - sample.height() + horizon.scale - 1) / horizon.scale;
                for (int period = (int) Math.min(periods - 1, horizon.periods); period < horizon.periods; period++)
                    horizon.confirmed[period][sample.bucket()]++;
                horizon.count[sample.bucket()]++;
                horizon.rateSum[sample.bucket()] += sample.rate();
            }
            recorded++;
        }
        if (firstHeight == 0 && recorded > 0) firstHeight = height;
    }

    public synchronized Estimate estimate(int requested, boolean conservative) {
        long span = firstHeight == 0 ? 0 : bestHeight - firstHeight;
        long historic = historicalFirst == 0 || bestHeight - historicalBest > 6048 ? 0 : historicalBest - historicalFirst;
        int target = (int) Math.min(Math.max(2, requested), Math.min(1008, Math.max(span, historic) / 2));
        if (target < 2) return new Estimate(null, target);
        double rate = Math.max(combined(target / 2, .6, true), combined(target, .85, true));
        rate = Math.max(rate, combined(target * 2, .95, !conservative));
        if (conservative || rate == -1) {
            if (target * 2 <= 12) rate = Math.max(rate, median(horizons[1], target * 2, .95));
            if (target * 2 <= 48) rate = Math.max(rate, median(horizons[2], target * 2, .95));
        }
        return new Estimate(rate < 0 || Math.round(rate) == 0 ? null : Math.round(rate), target);
    }

    public synchronized void shutdown() {
        for (var id : List.copyOf(tracked.keySet())) removed(id);
    }

    private double combined(int target, double threshold, boolean shorter) {
        if (target < 1 || target > 1008) return -1;
        double result = median(horizons[target <= 12 ? 0 : target <= 48 ? 1 : 2], target, threshold);
        if (shorter) {
            for (int index = 1; index >= 0; index--) {
                var horizon = horizons[index];
                if (target <= horizon.periods * horizon.scale) continue;
                double candidate = median(horizon, horizon.periods * horizon.scale, threshold);
                if (candidate > 0 && (result == -1 || candidate < result)) result = candidate;
            }
        }
        return result;
    }

    private double median(Horizon horizon, int target, double threshold) {
        int period = (target + horizon.scale - 1) / horizon.scale - 1;
        if (period < 0 || period >= horizon.periods) return -1;
        var unconfirmed = new long[buckets.length];
        for (var sample : tracked.values()) if (bestHeight - sample.height() >= target) unconfirmed[sample.bucket()]++;
        double confirmed = 0, total = 0, failed = 0, partial = 0;
        long extra = 0;
        int near = buckets.length - 1, bestNear = near, bestFar = near;
        boolean newRange = true, found = false;
        for (int bucket = buckets.length - 1; bucket >= 0; bucket--) {
            if (newRange) { near = bucket; newRange = false; }
            confirmed += horizon.confirmed[period][bucket];
            partial += horizon.count[bucket]; total += horizon.count[bucket];
            failed += horizon.failed[period][bucket]; extra += unconfirmed[bucket];
            if (partial < horizon.sufficient / (1 - horizon.decay)) continue;
            partial = 0;
            if (confirmed / (total + failed + extra) < threshold) continue;
            found = true; bestNear = near; bestFar = bucket; newRange = true;
            confirmed = 0; total = 0; failed = 0; extra = 0;
        }
        if (!found) return -1;
        double weight = 0;
        for (int bucket = bestFar; bucket <= bestNear; bucket++) weight += horizon.count[bucket];
        if (weight == 0) return -1;
        weight /= 2;
        for (int bucket = bestFar; bucket <= bestNear; bucket++) {
            if (horizon.count[bucket] < weight) weight -= horizon.count[bucket];
            else return horizon.rateSum[bucket] / horizon.count[bucket];
        }
        return -1;
    }

    private final class Horizon {
        final int periods, scale;
        final double decay, sufficient;
        final double[] count = new double[buckets.length], rateSum = new double[buckets.length];
        final double[][] confirmed, failed;
        Horizon(int periods, int scale, double decay, double sufficient) {
            this.periods = periods; this.scale = scale; this.decay = decay; this.sufficient = sufficient;
            confirmed = new double[periods][buckets.length]; failed = new double[periods][buckets.length];
        }
        void decay() {
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                count[bucket] *= decay; rateSum[bucket] *= decay;
                for (int period = 0; period < periods; period++) {
                    confirmed[period][bucket] *= decay; failed[period][bucket] *= decay;
                }
            }
        }
    }

    /** Core 32.1 fee-estimates v309900 format (little endian and CompactSize vectors). */
    public synchronized void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(Integer.reverseBytes(309900)); output.writeInt(Integer.reverseBytes(Math.toIntExact(bestHeight)));
            long span = firstHeight == 0 ? 0 : bestHeight - firstHeight;
            long historicalSpan = historicalFirst == 0 || bestHeight - historicalBest > 6048 ? 0 : historicalBest - historicalFirst;
            output.writeInt(Integer.reverseBytes(Math.toIntExact(span > historicalSpan / 2 ? firstHeight : historicalFirst)));
            output.writeInt(Integer.reverseBytes(Math.toIntExact(span > historicalSpan / 2 ? bestHeight : historicalBest)));
            writeVector(output, buckets);
            for (int index : new int[]{1, 0, 2}) {
                var horizon = horizons[index];
                output.writeLong(Long.reverseBytes(Double.doubleToLongBits(horizon.decay)));
                output.writeInt(Integer.reverseBytes(horizon.scale));
                writeVector(output, horizon.rateSum); writeVector(output, horizon.count);
                output.writeByte(horizon.periods);
                for (var period : horizon.confirmed) writeVector(output, period);
                output.writeByte(horizon.periods);
                for (var period : horizon.failed) writeVector(output, period);
            }
        }
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    public synchronized boolean load(Path path) {
        var restored = new BlockPolicyFeeEstimator();
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            if (Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - 60 * 3600_000L
                    || input.readInt() != Integer.reverseBytes(309900)) return false;
            restored.bestHeight = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            restored.historicalFirst = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            restored.historicalBest = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (restored.bestHeight < 0 || restored.historicalFirst < 0 || restored.historicalBest < restored.historicalFirst
                    || restored.historicalBest > restored.bestHeight) return false;
            var encodedBuckets = new double[buckets.length]; readVector(input, encodedBuckets);
            if (!Arrays.equals(encodedBuckets, buckets)) return false;
            for (int index : new int[]{1, 0, 2}) {
                var horizon = restored.horizons[index];
                if (finite(input) != horizon.decay || Integer.reverseBytes(input.readInt()) != horizon.scale) return false;
                readVector(input, horizon.rateSum); readVector(input, horizon.count);
                if (input.readUnsignedByte() != horizon.periods) return false;
                for (var period : horizon.confirmed) readVector(input, period);
                if (input.readUnsignedByte() != horizon.periods) return false;
                for (var period : horizon.failed) readVector(input, period);
            }
            if (input.read() != -1) return false;
            bestHeight = restored.bestHeight; historicalFirst = restored.historicalFirst; historicalBest = restored.historicalBest;
            firstHeight = 0; tracked.clear();
            for (int i = 0; i < horizons.length; i++) {
                var to = horizons[i]; var from = restored.horizons[i];
                System.arraycopy(from.count, 0, to.count, 0, buckets.length);
                System.arraycopy(from.rateSum, 0, to.rateSum, 0, buckets.length);
                for (int p = 0; p < to.periods; p++) {
                    System.arraycopy(from.confirmed[p], 0, to.confirmed[p], 0, buckets.length);
                    System.arraycopy(from.failed[p], 0, to.failed[p], 0, buckets.length);
                }
            }
            return true;
        } catch (IOException e) { return false; }
    }
    private static double finite(DataInputStream input) throws IOException {
        double value = Double.longBitsToDouble(Long.reverseBytes(input.readLong()));
        if (!Double.isFinite(value) || value < 0) throw new IOException("Invalid fee statistics");
        return value;
    }

    private static void writeVector(DataOutputStream output, double[] values) throws IOException {
        if (values.length >= 253) throw new IOException("Unexpected fee bucket count");
        output.writeByte(values.length);
        for (double value : values) output.writeLong(Long.reverseBytes(Double.doubleToLongBits(value)));
    }

    private static void readVector(DataInputStream input, double[] values) throws IOException {
        if (input.readUnsignedByte() != values.length) throw new IOException("Unexpected fee vector size");
        for (int i = 0; i < values.length; i++) values[i] = finite(input);
    }
}
