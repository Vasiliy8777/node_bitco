package ru.bitcoin.node.rvn.consensus;
import java.math.BigInteger;
/** Work contribution implied by a compact target. This is not proof-of-work verification. */
public final class RvnBlockProof {
    private static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);
    private RvnBlockProof() {}
    public static BigInteger fromBits(long bits, BigInteger powLimit) {
        if (powLimit == null || powLimit.signum() <= 0 || powLimit.bitLength() > 256)
            throw new IllegalArgumentException("powLimit");
        BigInteger target = CompactTarget.decode(bits);
        if (target.compareTo(powLimit) > 0) throw new IllegalArgumentException("target exceeds powLimit");
        return TWO_256.divide(target.add(BigInteger.ONE));
    }
    public static BigInteger accumulate(BigInteger parentWork, BigInteger blockWork) {
        if (parentWork == null || blockWork == null || parentWork.signum() < 0 || blockWork.signum() <= 0)
            throw new IllegalArgumentException("work");
        return parentWork.add(blockWork);
    }
}
