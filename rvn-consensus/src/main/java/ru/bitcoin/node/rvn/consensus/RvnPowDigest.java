package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Byte-order-explicit comparison of a VERIFIED 256-bit PoW digest to the
 * declared compact target. Does not calculate X16R, X16RV2 or KawPoW.
 */
public final class RvnPowDigest {
    private RvnPowDigest() {
    }

    public enum ByteOrder {BIG_ENDIAN, LITTLE_ENDIAN}

    public static BigInteger unsignedValue(byte[] digest, ByteOrder order) {
        Objects.requireNonNull(order);
        if (digest == null || digest.length != 32)
            throw new IllegalArgumentException("PoW digest must be 32 bytes");
        byte[] bigEndian = digest.clone();
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < 16; i++) {
                byte temp = bigEndian[i];
                bigEndian[i] = bigEndian[31 - i];
                bigEndian[31 - i] = temp;
            }
        }
        return new BigInteger(1, bigEndian);
    }

    public static boolean meetsTarget(byte[] verifiedDigest, ByteOrder order,
                                      long bits, BigInteger powLimit) {
        BigInteger target = RvnDifficultyScreen.requireWithinLimit(bits, powLimit);
        return unsignedValue(verifiedDigest, order).compareTo(target) <= 0;
    }
}
