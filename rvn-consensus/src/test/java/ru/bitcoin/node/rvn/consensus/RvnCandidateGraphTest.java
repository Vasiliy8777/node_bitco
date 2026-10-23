package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class RvnCandidateGraphTest {
    private static RvnHash256 id(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    private static final BigInteger LIMIT = CompactTarget.decode(0x1e00ffffL);

    @Test
    void branchesAndClaimedWorkAreNotActiveChain() {
        var graph = new RvnCandidateGraph(4, LIMIT, id(1), 100, BigInteger.TEN);
        var a = new RvnHeaderCandidate(id(2), id(1), 0x1e00ffffL, 1000, 101, true);
        var b = new RvnHeaderCandidate(id(3), id(2), 0x1e00ffffL, 1001, 102, true);
        var fork = new RvnHeaderCandidate(id(4), id(2), 0x1e00ffffL, 1002, 102, true);
        assertEquals(101, graph.add(a).height());
        assertEquals(102, graph.add(b).height());
        graph.add(fork);
        assertEquals(3, graph.size());
        assertEquals(2, graph.pathToAnchor(id(3)).size());
        assertEquals(graph.find(id(3)).orElseThrow().claimedCumulativeWork(),
                graph.find(id(4)).orElseThrow().claimedCumulativeWork());
        assertEquals(3, graph.byClaimedWork().size());
        assertEquals(3, graph.size());
    }

    @Test
    void rejectsOrphansBadHeightsAndCapacity() {
        var graph = new RvnCandidateGraph(1, LIMIT, id(1), 0, BigInteger.ZERO);
        assertThrows(IllegalArgumentException.class, () -> graph.add(new RvnHeaderCandidate(id(2), id(9), 0x1e00ffffL, 0, 1, true)));
        assertThrows(IllegalArgumentException.class, () -> graph.add(new RvnHeaderCandidate(id(2), id(1), 0x1e00ffffL, 0, 2, true)));
        var first = new RvnHeaderCandidate(id(2), id(1), 0x1e00ffffL, 0, 1, true);
        assertSame(graph.add(first), graph.add(first));
        assertThrows(IllegalStateException.class, () -> graph.add(new RvnHeaderCandidate(id(3), id(2), 0x1e00ffffL, 0, 2, true)));
    }

    @Test
    void hashValueSemanticsAndDefensiveCopy() {
        byte[] bytes = new byte[32];
        bytes[0] = 1;
        var h = new RvnHash256(bytes);
        bytes[0] = 2;
        assertEquals(1, h.wire()[0]);
        assertEquals(h, new RvnHash256(h.wire()));
        assertEquals(64, h.toString().length());
    }

    @Test
    void proofGateDefaultsToDeny() {
        var c = new RvnHeaderCandidate(id(2), id(1), 0x1e00ffffL, 0, 1, true);
        assertThrows(SecurityException.class, () -> RvnHeaderProofGate.requireVerified(c, new byte[120], RvnHeaderProofGate.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> RvnHeaderProofGate.requireVerified(c, new byte[80], RvnHeaderProofGate.unavailable()));
    }
}
