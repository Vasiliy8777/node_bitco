package ru.bitcoin.node.rvn.consensus;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RvnValidatedHeaderIndexTest {
    private static RvnHash256 id(int n) { byte[] b = new byte[32]; b[0] = (byte)n; return new RvnHash256(b); }
    private static byte[] raw(RvnHash256 parent, int height, long bits) {
        byte[] b = new byte[120];
        System.arraycopy(parent.wire(), 0, b, 4, 32);
        for (int i=0;i<4;i++) { b[72+i]=(byte)(bits >>> (8*i)); b[76+i]=(byte)(height >>> (8*i)); }
        return b;
    }
    private static final long BITS = 0x207fffffL;
    private static final BigInteger LIMIT = CompactTarget.decode(BITS);
    private static RvnHeaderCandidate candidate(int id, RvnHash256 parent, int height) {
        return new RvnHeaderCandidate(id(id), parent, BITS, 0, height, true);
    }
    private static final RvnHeaderProofGate.Verifier TEST_ONLY = new RvnHeaderProofGate.Verifier() {
        public boolean blockIdMatches(RvnHeaderCandidate c, byte[] b) { return true; }
        public boolean validProofOfWork(RvnHeaderCandidate c, byte[] b) { return true; }
        public boolean validContext(RvnHeaderCandidate c) { return true; }
    };
    @Test void rejectsByDefaultWithoutMutation() {
        var index = new RvnValidatedHeaderIndex(10, id(90), 0, BigInteger.ZERO, LIMIT);
        var c = candidate(1, id(90), 1);
        assertThrows(SecurityException.class, () -> index.admit(c, raw(id(90),1,BITS), RvnHeaderProofGate.unavailable()));
        assertEquals(0, index.size());
        assertEquals(id(90), index.bestId());
    }
    @Test void refusesMetadataMismatchEvenWithPermissiveTestVerifier() {
        var index = new RvnValidatedHeaderIndex(10, id(90), 0, BigInteger.ZERO, LIMIT);
        var c = candidate(1, id(90), 1);
        assertThrows(SecurityException.class, () -> index.admit(c, raw(id(91),1,BITS), TEST_ONLY));
        assertEquals(0, index.size());
    }
    @Test void branchesWorkAndCommonAncestorWithTestVerifier() {
        var index = new RvnValidatedHeaderIndex(10, id(90), 0, BigInteger.ZERO, LIMIT);
        var a = candidate(1, id(90), 1);
        var b = candidate(2, a.id(), 2);
        var fork = candidate(3, a.id(), 2);
        index.admit(a, raw(id(90),1,BITS), TEST_ONLY);
        index.admit(b, raw(a.id(),2,BITS), TEST_ONLY);
        index.admit(fork, raw(a.id(),2,BITS), TEST_ONLY);
        assertEquals(a.id(), index.commonAncestor(b.id(),fork.id()));
        assertEquals(2,index.bestHeight());
        assertEquals(2,index.pathToAnchor(b.id()).size());
        assertEquals(3,index.size());
        assertTrue(index.bestWork().signum()>0);
    }
    @Test void orphanRejectedAndCapacityRespected() {
        var index = new RvnValidatedHeaderIndex(1,id(90),0,BigInteger.ZERO,LIMIT);
        var a = candidate(1,id(90),1);
        assertThrows(IllegalArgumentException.class, () -> index.admit(candidate(2,id(1),2),raw(id(1),2,BITS),TEST_ONLY));
        index.admit(a,raw(id(90),1,BITS),TEST_ONLY);
        assertThrows(IllegalStateException.class, () -> index.admit(candidate(2,a.id(),2),raw(a.id(),2,BITS),TEST_ONLY));
    }
}
