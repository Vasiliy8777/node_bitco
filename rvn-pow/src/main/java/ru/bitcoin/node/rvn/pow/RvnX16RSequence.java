package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * X16R's sixteen algorithm IDs are the final sixteen hexadecimal digits of
 * the previous block hash as displayed by Ravencoin Core (high nibble first).
 * A header stores that hash in reversed, little-endian wire byte order.
 * This is only the ordering stage, NOT the sixteen cryptographic hashes.
 */
public final class RvnX16RSequence {
    private RvnX16RSequence() {
    }

    public static final String[] ALGORITHMS = {
            "BLAKE", "BMW", "GROESTL", "JH", "KECCAK", "SKEIN", "LUFFA", "CUBEHASH",
            "SHAVITE", "SIMD", "ECHO", "HAMSI", "FUGUE", "SHABAL", "WHIRLPOOL", "SHA512"
    };

    public static int[] fromPreviousHashWire(byte[] previousHashWire) {
        if (previousHashWire == null || previousHashWire.length != 32)
            throw new IllegalArgumentException("Previous hash must be 32 bytes");
        int[] result = new int[16];
        for (int i = 0; i < 8; i++) {
            int b = previousHashWire[7 - i] & 0xff;
            result[2 * i] = (b >>> 4) & 15;
            result[2 * i + 1] = b & 15;
        }
        return result;
    }

    public static int[] fromHeader(byte[] raw) {
        if (raw == null || raw.length != 80)
            throw new IllegalArgumentException("X16R/X16RV2 header must be 80 bytes");
        return fromPreviousHashWire(Arrays.copyOfRange(raw, 4, 36));
    }

    public static String hexOrder(byte[] previousHashWire) {
        StringBuilder sb = new StringBuilder(16);
        for (int id : fromPreviousHashWire(previousHashWire)) sb.append(Character.forDigit(id, 16));
        return sb.toString();
    }
}
