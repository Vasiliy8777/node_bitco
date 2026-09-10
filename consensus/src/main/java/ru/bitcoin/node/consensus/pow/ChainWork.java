package ru.bitcoin.node.consensus.pow;

import java.math.BigInteger;

public final class ChainWork {

    private static final BigInteger TWO_256 =
            BigInteger.ONE.shiftLeft(256);

    private ChainWork() {
    }

    public static BigInteger blockWork(
            BigInteger target
    ) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "target must not be null"
            );
        }

        if (target.signum() <= 0) {
            throw new IllegalArgumentException(
                    "target must be positive"
            );
        }

        if (target.compareTo(TWO_256) >= 0) {
            throw new IllegalArgumentException(
                    "target must be less than 2^256"
            );
        }

        return TWO_256.divide(
                target.add(BigInteger.ONE)
        );
    }

    public static BigInteger blockWork(
            long bits
    ) {
        return blockWork(
                CompactTarget.decode(bits)
        );
    }

    public static BigInteger add(
            BigInteger chainWork,
            BigInteger blockWork
    ) {
        if (chainWork == null
                || blockWork == null) {

            throw new IllegalArgumentException(
                    "work values must not be null"
            );
        }

        if (chainWork.signum() < 0
                || blockWork.signum() < 0) {

            throw new IllegalArgumentException(
                    "work cannot be negative"
            );
        }

        return chainWork.add(blockWork);
    }
}