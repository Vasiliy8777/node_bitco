package ru.bitcoin.node.rvn.pow;

/**
 * The complete eight-selector ProgPoW math operation table (uint32 arithmetic).
 */
public final class RvnProgpowMath {
    private RvnProgpowMath() {
    }

    public static int apply(int a, int b, int selector) {
        return switch (selector & 7) {
            case 0 -> a + b;
            case 1 -> a * b;
            case 2 -> RvnKawpowMath.mulHiUnsigned(a, b);
            case 3 -> Integer.compareUnsigned(a, b) <= 0 ? a : b;
            case 4 -> Integer.compareUnsigned(a, b) >= 0 ? a : b;
            case 5 -> Integer.rotateLeft(a, b & 31);
            case 6 -> Integer.rotateRight(a, b & 31);
            default -> Integer.bitCount(a) + Integer.bitCount(b);
        };
    }
}
