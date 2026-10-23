package ru.bitcoin.node.rvn.pow;

/**
 * Exact 11-selector ProgPoW 0.9.4 unsigned 32-bit arithmetic table.
 */
public final class RvnProgpowMath {
    private RvnProgpowMath() {
    }

    public static int apply(int a, int b, int selector) {
        return switch (Integer.remainderUnsigned(selector, 11)) {
            case 0 -> a + b;
            case 1 -> a * b;
            case 2 -> RvnKawpowMath.mulHiUnsigned(a, b);
            case 3 -> Integer.compareUnsigned(a, b) <= 0 ? a : b;
            case 4 -> Integer.rotateLeft(a, b & 31);
            case 5 -> Integer.rotateRight(a, b & 31);
            case 6 -> a & b;
            case 7 -> a | b;
            case 8 -> a ^ b;
            case 9 -> Integer.numberOfLeadingZeros(a) + Integer.numberOfLeadingZeros(b);
            default -> Integer.bitCount(a) + Integer.bitCount(b);
        };
    }
}
