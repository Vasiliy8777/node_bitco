package ru.bitcoin.node.rvn.pow;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RvnStages53To60Test {
    @Test void referenceOrderFromPreviousBlockDisplay() {
        // Displayed previous hash ends in 070ef6eb6da46c8eae389df90;
        // last 16 hex digits are 6da46c8eae389df90? Use a known 16-char suffix below.
        byte[] prev=new byte[32];
        byte[] lastDisplayed= HexFormat.of().parseHex("4f0da52072c99492");
        for(int i=0;i<8;i++)prev[i]=lastDisplayed[7-i];
        assertEquals("4f0da52072c99492",RvnX16RSequence.hexOrder(prev));
        assertArrayEquals(new int[]{4,15,0,13,10,5,2,0,7,2,12,9,9,4,9,2},
                RvnX16RSequence.fromPreviousHashWire(prev));
    }
    @Test void sixteenRoundsWithRepeatAndDefensiveCopies() throws Exception {
        var primitives=new RvnX16RPrimitive[16];
        int[] counts=new int[16];
        for(int i=0;i<16;i++) {
            final int id=i;
            primitives[i]=input->{counts[id]++;assertTrue(input.length==80||input.length==64);
                byte[] next=new byte[64];next[0]=(byte)id;return next;};
        }
        var chain=new RvnX16RChain(primitives);
        byte[] header=new byte[80];
        byte[] displayed= HexFormat.of().parseHex("4f0da52072c99492");
        for(int i=0;i<8;i++)header[4+i]=displayed[7-i];
        assertEquals(2,chain.digest(header)[0]);
        assertEquals(16,Arrays.stream(counts).sum());
        assertEquals(3,counts[9]);
        assertEquals(3,counts[2]);
    }
    @Test void invalidPrimitiveCannotProduceHash() {
        var primitives=new RvnX16RPrimitive[16];
        for (int i = 0; i < primitives.length; i++) {
            primitives[i] = input -> new byte[32];
        }
        assertThrows(RvnPowException.class,()->new RvnX16RChain(primitives).digest(new byte[80]));
    }
    @Test void kawpowMustComputeMixNotTrustHeader() throws Exception {
        byte[] raw=new byte[120];raw[88]=7;
        var engine=new RvnKawpowMixEngine(){
            @Override public RvnKawpowMixResult calculateKawpow(byte[] input) {
                byte[] mix=new byte[32];mix[0]=7;
                return new RvnKawpowMixResult(mix,new RvnPowResult(new byte[32],new byte[32]));
            }
            @Override public RvnPowResult calculate(RvnPowAlgorithm a,byte[] b) {
                throw new AssertionError("Must not use generic KawPoW calculation");
            }
        };
        assertNotNull(RvnKawpowProof.requireValidMix(engine,raw));
        raw[88]=8;
        assertThrows(RvnPowException.class,()->RvnKawpowProof.requireValidMix(engine,raw));
        assertThrows(RvnPowException.class,()->new RvnPowRouter(
            new RvnPowSchedule(100,200),(a,b)->new RvnPowResult(new byte[32],new byte[32]))
            .calculate(200,new byte[120]));
    }
}
