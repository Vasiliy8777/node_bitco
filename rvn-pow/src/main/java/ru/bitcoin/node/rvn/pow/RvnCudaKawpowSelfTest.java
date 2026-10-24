package ru.bitcoin.node.rvn.pow;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HexFormat;

/** Explicit GPU diagnostic; allocates a real epoch DAG. Never submits blocks. */
public final class RvnCudaKawpowSelfTest {
    private RvnCudaKawpowSelfTest() {}
    public static void run(RvnCudaKawpowWorker worker) throws IOException {
        check(worker, 0, "00".repeat(32), "0",
                "6e97b47b134fda0c7888802988e1a373affeb28bcd813b6e9a0fc669c935d03a",
                "e601a7257a70dc48fccc97a7330d704d776047623b92883d77111fb36870f3d1");
        check(worker, 49, "63155f732f2bf556967f906155b510c917e48e99685ead76ea83f4eca03ab12b", "0000000007073c07",
                "d36f7e815ee09e74eceb9c96993a3d681edf2bf0921fc7bb710364042db99777",
                "e7ced124598fd2500a55ad9f9f48e3569327fe50493c77a4ac9799b96efb9463");
        check(worker, 50, "9e7248f20914913a73d80a70174c331b1d34f260535ac3631d770e656b5dd922", "00000000076e482e",
                "d6dc634ae837e2785b347648ea515e25e5d8821ae0b95e1c2a9c2d497e0dcfbd",
                "ab0ad7ef8d8ee317dd12d10310aceed7321d34fb263791c2de5776a6658d177e");
        check(worker, 30000, "d34519f72c97cae8892c277776259db3320820cb5279a299d0ef1e155e5c6454", "005db8607994ff30",
                "de0348b69bf91dfe2c3d3dba6f0132e9048a5284e57b8d9d20adc5f3dc0d3236",
                "c7953d848cda6e304f77b4c6d735645c8e8508a5e74c9e9814ef37b19087cd6c");
        var maximum = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        var boundary = worker.search(30000, new byte[32], -3L, 3, maximum);
        if (boundary.overflow() || boundary.candidates().size() != 3)
            throw new IOException("CUDA unsigned nonce boundary mismatch");
        for (long nonce : new long[]{-3L, -2L, -1L})
            if (boundary.candidates().stream().noneMatch(candidate -> candidate.nonce() == nonce))
                throw new IOException("CUDA lost nonce at unsigned boundary");
        var vectorHeader = HexFormat.of().parseHex("d34519f72c97cae8892c277776259db3320820cb5279a299d0ef1e155e5c6454");
        long vectorNonce = Long.parseUnsignedLong("005db8607994ff30", 16);
        var exactTarget = new BigInteger("c7953d848cda6e304f77b4c6d735645c8e8508a5e74c9e9814ef37b19087cd6c", 16);
        if (worker.search(30000, vectorHeader, vectorNonce, 1, exactTarget).candidates().size() != 1
                || !worker.search(30000, vectorHeader, vectorNonce, 1, exactTarget.subtract(BigInteger.ONE)).candidates().isEmpty())
            throw new IOException("CUDA target equality/boundary mismatch");
        System.out.println("CUDA nonce/target boundary PASS");
        long started = System.nanoTime();
        var bounded = worker.search(30000, vectorHeader, vectorNonce, 4096, BigInteger.ZERO);
        if (bounded.overflow()) throw new IOException("Unexpected CUDA zero-target overflow");
        System.out.printf(java.util.Locale.ROOT, "CUDA bounded batch tested=%d elapsedMs=%.1f%n",
                bounded.tested(), (System.nanoTime() - started) / 1_000_000.0);
    }
    private static void check(RvnCudaKawpowWorker worker, long height, String header, String nonce,
                              String mix, String hash) throws IOException {
        var hex = HexFormat.of();
        var batch = worker.search(height, hex.parseHex(header), Long.parseUnsignedLong(nonce, 16), 1,
                BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
        if (batch.overflow() || batch.candidates().size() != 1
                || !hex.formatHex(batch.candidates().getFirst().mix()).equals(mix)
                || !hex.formatHex(batch.candidates().getFirst().hash()).equals(hash))
            throw new IOException("CUDA Core vector mismatch at height " + height);
        System.out.println("CUDA Core vector PASS height=" + height);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("worker.py temporary-directory python-executable");
        try (var worker = new RvnCudaKawpowWorker(args[2], Path.of(args[0]), Path.of(args[1]), 0, 1L << 30)) {
            run(worker);
        }
    }
}
