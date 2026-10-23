package ru.bitcoin.node.rvn.pow;

import java.util.HexFormat;

/** Independent known answers from Core src/crypto/ethash/progpow_test_vectors.hpp. */
public final class RvnKawpowSelfTest {
    private RvnKawpowSelfTest() {}

    public static void requireValid(RvnKawpowLightEngine engine) {
        var result = engine.compute(0, new byte[32], 0);
        var hex = HexFormat.of();
        if (!hex.formatHex(result.mixHash()).equals(
                "6e97b47b134fda0c7888802988e1a373affeb28bcd813b6e9a0fc669c935d03a")
                || !hex.formatHex(result.finalHash()).equals(
                "e601a7257a70dc48fccc97a7330d704d776047623b92883d77111fb36870f3d1"))
            throw new IllegalStateException("KawPoW Core known-answer self-test failed");
    }
}
