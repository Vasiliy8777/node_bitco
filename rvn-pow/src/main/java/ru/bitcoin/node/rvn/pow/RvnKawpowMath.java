package ru.bitcoin.node.rvn.pow;

/**
 * 32-bit ProgPoW/KawPoW arithmetic. Unsigned overflow is intentional.
 */
public final class RvnKawpowMath {
    private RvnKawpowMath() {
    }

    public static int rotl(int value, int bits) {
        return Integer.rotateLeft(value, bits & 31);
    }

    public static int rotr(int value, int bits) {
        return Integer.rotateRight(value, bits & 31);
    }

    public static int clz(int value) {
        return Integer.numberOfLeadingZeros(value);
    }

    public static int popcount(int value) {
        return Integer.bitCount(value);
    }

    public static int mulHiUnsigned(int a, int b) {
        return (int) ((Integer.toUnsignedLong(a) * Integer.toUnsignedLong(b)) >>> 32);
    }

    /**
     * ProgPoW merge operator selected by selector modulo four.
     */
    public static int merge(int a, int b, int selector) {
        return switch (selector & 3) {
            case 0 -> (a * 33) + b;
            case 1 -> (a ^ b) * 33;
            case 2 -> rotl(a, ((selector >>> 16) % 31) + 1) ^ b;
            default -> rotr(a, ((selector >>> 16) % 31) + 1) ^ b;
        };
    }
}
