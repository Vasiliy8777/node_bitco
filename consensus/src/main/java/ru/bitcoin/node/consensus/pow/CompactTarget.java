package ru.bitcoin.node.consensus.pow;

import java.math.BigInteger;

public final class CompactTarget {

    private static final BigInteger BASE =
            BigInteger.valueOf(256);

    private CompactTarget() {
    }

    public static BigInteger decode(long compact) {
        if (compact < 0 || compact > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(
                    "compact must fit uint32"
            );
        }

        int size =
                (int) ((compact >>> 24) & 0xFF);

        long word =
                compact & 0x007F_FFFFL;

        boolean negative =
                (compact & 0x0080_0000L) != 0;

        BigInteger value;

        if (size <= 3) {
            long shifted =
                    word >>> (8 * (3 - size));

            value =
                    BigInteger.valueOf(shifted);
        } else {
            value =
                    BigInteger.valueOf(word)
                            .shiftLeft(
                                    8 * (size - 3)
                            );
        }

        if (negative && value.signum() != 0) {
            value = value.negate();
        }

        return value;
    }
    public static long encode(BigInteger value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "target cannot be negative"
            );
        }

        if (value.signum() == 0) {
            return 0;
        }

        int size =
                (value.bitLength() + 7) / 8;

        long compact;

        if (size <= 3) {
            compact =
                    value.longValue()
                            << (8 * (3 - size));
        } else {
            BigInteger shifted =
                    value.shiftRight(
                            8 * (size - 3)
                    );

            compact =
                    shifted.longValue();
        }

        /*
         * Если старший бит mantissa установлен,
         * сдвигаем число вправо на байт,
         * а exponent увеличиваем.
         */
        if ((compact & 0x0080_0000L) != 0) {
            compact >>>= 8;
            size++;
        }

        compact &= 0x007F_FFFFL;
        compact |= ((long) size) << 24;

        return compact & 0xFFFF_FFFFL;
    }
}