package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;

/**
 * Bitcoin-family nBits encoding. This is only a primitive, NOT Ravencoin PoW validation.
 */
public final class CompactTarget {
    private CompactTarget() {
    }

    /**
     * Decode an unsigned 32-bit nBits representation and reject negative/overflow/zero targets.
     */
    public static BigInteger decode(long compact) {
        if ((compact & ~0xffffffffL) != 0) throw new IllegalArgumentException("nBits must be uint32");
        int size = (int) (compact >>> 24);
        long word = compact & 0x007fffffL;
        if ((compact & 0x00800000L) != 0 && word != 0) throw new IllegalArgumentException("negative target");
        boolean overflow = word != 0 && (size > 34 || (word > 0xffL && size > 33) || (word > 0xffffL && size > 32));
        if (overflow) throw new IllegalArgumentException("target overflows 256 bits");
        BigInteger target = BigInteger.valueOf(word);
        target = size <= 3 ? target.shiftRight(8 * (3 - size)) : target.shiftLeft(8 * (size - 3));
        if (target.signum() <= 0 || target.bitLength() > 256) throw new IllegalArgumentException("invalid target");
        return target;
    }

    /**
     * Compare unsigned 256-bit proof value to the decoded target (inclusive).
     */
    public static boolean meetsTarget(byte[] hashBigEndian, long compact) {
        if (hashBigEndian == null || hashBigEndian.length != 32)
            throw new IllegalArgumentException("hash must be 32 bytes");
        return new BigInteger(1, hashBigEndian).compareTo(decode(compact)) <= 0;
    }
}
