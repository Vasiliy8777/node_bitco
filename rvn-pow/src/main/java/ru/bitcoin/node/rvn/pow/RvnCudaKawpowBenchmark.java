package ru.bitcoin.node.rvn.pow;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Locale;

/** Standalone local tuning; stop other CUDA miners first. Never submits blocks. */
public final class RvnCudaKawpowBenchmark {
    private RvnCudaKawpowBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) throw new IllegalArgumentException("worker.py temporary-directory python-executable height [permutation]");
        long height = Long.parseLong(args[3]);
        try (var worker = new RvnCudaKawpowWorker(args[2], Path.of(args[0]), Path.of(args[1]), 0, 1L << 30)) {
            RvnCudaKawpowSelfTest.run(worker);
            if (args.length == 5 && args[4].equals("permutation")) {
                comparePermutation(worker, height);
                return;
            }
            byte[] header = new byte[32];
            // Initialize the real epoch and independently audit every diagnostic candidate.
            worker.search(height, header, 0, 8, BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
            int[] sizes = {4096, 8192, 16384, 32768, 65536};
            double[] rates = new double[sizes.length];
            long nonce = 8;
            for (int trial = 0; trial < 3; trial++) {
                // Reverse alternate sweeps to reduce ordering/thermal bias.
                for (int position = 0; position < sizes.length; position++) {
                    int index = trial % 2 == 0 ? position : sizes.length - 1 - position;
                    int size = sizes[index];
                    worker.search(height, header, nonce, size, BigInteger.ZERO); nonce += size;
                    long start = System.nanoTime(), elapsed, hashes = 0, longest = 0;
                    do {
                        long batchStart = System.nanoTime();
                        var result = worker.search(height, header, nonce, size, BigInteger.ZERO);
                        longest = Math.max(longest, System.nanoTime() - batchStart);
                        if (result.overflow() || result.tested() != size || !result.candidates().isEmpty())
                            throw new IllegalStateException("Invalid benchmark search result");
                        nonce += size; hashes += size;
                        elapsed = System.nanoTime() - start;
                    } while (elapsed < 1_000_000_000L);
                    double rate = hashes * 1000.0 / elapsed;
                    rates[index] += rate;
                    System.out.printf(Locale.ROOT, "BENCH trial=%d size=%d MHs=%.3f maxBatchMs=%.3f%n",
                            trial + 1, size, rate, longest / 1_000_000.0);
                }
            }
            for (int i = 0; i < sizes.length; i++)
                System.out.printf(Locale.ROOT, "MEAN size=%d MHs=%.3f%n", sizes[i], rates[i] / 3);
        }
    }

    private static void comparePermutation(RvnCudaKawpowWorker worker, long height) throws Exception {
        byte[] header = new byte[32];
        header[0] = 0x12; header[1] = 0x34; header[7] = (byte) 0xab;
        worker.hashBatch(java.util.List.of(new RvnCudaKawpowWorker.Work(height, header, 0)));
        var rates = new double[2][6];
        long counter = 0;
        for (int trial = 0; trial < 6; trial++) for (int position = 0; position < 2; position++) {
            int mode = trial % 2 == 0 ? position : 1 - position;
            long began = System.nanoTime(), hashes = 0;
            do {
                var result = mode == 0 ? worker.search(height, header, counter, 65536, BigInteger.ZERO)
                        : worker.searchPermuted(height, header, counter, 65536, BigInteger.ZERO);
                if (result.overflow() || !result.candidates().isEmpty()) throw new IllegalStateException("Unexpected zero-target result");
                counter += 65536; hashes += 65536;
            } while (System.nanoTime() - began < 2_000_000_000L);
            double rate = hashes * 1000.0 / (System.nanoTime() - began);
            rates[mode][trial] = rate;
            System.out.printf(Locale.ROOT, "TRIAL %d mode=%s MHs=%.3f%n", trial + 1, mode == 0 ? "sequential" : "permuted", rate);
        }
        java.util.Arrays.sort(rates[0]); java.util.Arrays.sort(rates[1]);
        double raw = (rates[0][2] + rates[0][3]) / 2, mixed = (rates[1][2] + rates[1][3]) / 2;
        System.out.printf(Locale.ROOT, "RESULT height=%d sequentialMHs=%.3f permutedMHs=%.3f deltaPercent=%.3f%n",
                height, raw, mixed, (mixed / raw - 1) * 100);
    }
}
