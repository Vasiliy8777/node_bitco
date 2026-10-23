package ru.bitcoin.node.rvn.consensus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RvnHeaderOrphanBufferTest {
    private static RvnHash256 id(int n) { byte[] b = new byte[32]; b[0] = (byte)n; return new RvnHash256(b); }
    private static RvnHeaderOrphanBuffer.Pending pending(int n, int parent) {
        byte[] raw = new byte[80];
        System.arraycopy(id(parent).wire(),0,raw,4,32);
        raw[72]=1;
        return new RvnHeaderOrphanBuffer.Pending(
            new RvnHeaderCandidate(id(n),id(parent),1,0,-1,false),raw);
    }
    @Test void boundedAndDrainsChildren() {
        var buffer = new RvnHeaderOrphanBuffer(2);
        assertTrue(buffer.add(pending(1,10)));
        assertFalse(buffer.add(pending(1,10)));
        assertTrue(buffer.add(pending(2,10)));
        assertEquals(2,buffer.drainChildren(id(10)).size());
        assertEquals(0,buffer.size());
        buffer.add(pending(3,11));
        buffer.add(pending(4,12));
        buffer.add(pending(5,13));
        assertEquals(2,buffer.size());
        assertEquals(0,buffer.drainChildren(id(11)).size());
    }
    @Test void pendingDefensiveCopyAndMetadata() {
        var p = pending(1,10);
        byte[] raw = p.raw(); raw[4] = 99;
        assertEquals(10,p.raw()[4]);
        var buffer = new RvnHeaderOrphanBuffer(2);
        assertTrue(buffer.add(p));
        byte[] wrong = p.raw(); wrong[4] = 22;
        assertThrows(SecurityException.class, () -> buffer.add(new RvnHeaderOrphanBuffer.Pending(p.candidate(),wrong)));
    }
}
