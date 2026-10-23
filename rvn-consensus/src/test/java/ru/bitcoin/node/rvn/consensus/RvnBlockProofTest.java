package ru.bitcoin.node.rvn.consensus;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;
class RvnBlockProofTest {
    private static final BigInteger LIMIT = CompactTarget.decode(0x1e00ffffL);
    @Test void workIsPositiveAndAdditive() {
        BigInteger work = RvnBlockProof.fromBits(0x1e00ffffL, LIMIT);
        assertTrue(work.signum() > 0);
        assertEquals(work.multiply(BigInteger.TWO), RvnBlockProof.accumulate(work, work));
    }
    @Test void rejectsTargetBeyondLimit() {
        assertThrows(IllegalArgumentException.class, () -> RvnBlockProof.fromBits(0x1f00ffffL, LIMIT));
        assertThrows(IllegalArgumentException.class, () -> RvnBlockProof.fromBits(0, LIMIT));
    }
    @Test void declaredTargetComparisonIsOnlyNumeric() {
        assertTrue(RvnDifficultyScreen.hashMeetsDeclaredTarget(new byte[32], 0x1e00ffffL, LIMIT));
        byte[] max = new byte[32];java.util.Arrays.fill(max, (byte)0xff);
        assertFalse(RvnDifficultyScreen.hashMeetsDeclaredTarget(max, 0x1e00ffffL, LIMIT));
    }
}
