package ru.bitcoin.node.rvn.consensus;
import java.math.BigInteger;
/** Numeric target checks only; DOES NOT verify expected difficulty at a height. */
public final class RvnDifficultyScreen {
    private RvnDifficultyScreen() {}
    public static BigInteger requireWithinLimit(long bits, BigInteger powLimit) {
        RvnBlockProof.fromBits(bits, powLimit);
        return CompactTarget.decode(bits);
    }
    public static boolean hashMeetsDeclaredTarget(byte[] verifiedPowHashBigEndian, long bits, BigInteger powLimit) {
        requireWithinLimit(bits, powLimit);
        return CompactTarget.meetsTarget(verifiedPowHashBigEndian, bits);
    }
}
