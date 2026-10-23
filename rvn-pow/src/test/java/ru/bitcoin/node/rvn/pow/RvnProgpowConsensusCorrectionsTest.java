package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RvnProgpowConsensusCorrectionsTest {
    @Test
    void mathAllElevenSelectors() {
        int a = 0x80000000, b = 3;
        int[] expected = {a + b, a * b, RvnKawpowMath.mulHiUnsigned(a, b), 3,
                Integer.rotateLeft(a, 3), Integer.rotateRight(a, 3), a & b, a | b, a ^ b,
                Integer.numberOfLeadingZeros(a) + Integer.numberOfLeadingZeros(b),
                Integer.bitCount(a) + Integer.bitCount(b)};
        for (int i = 0; i < 11; i++) assertEquals(expected[i], RvnProgpowMath.apply(a, b, i));
        assertEquals(expected[0], RvnProgpowMath.apply(a, b, 11));
        assertEquals(expected[10], RvnProgpowMath.apply(a, b, -1));
    }

    @Test
    void datasetUses512ParentsAndFourNodes() {
        assertEquals(512, RvnKawpowDatasetItem.PARENTS);
        var cache = RvnKawpowCache.build(new byte[32], 11);
        byte[] item = RvnKawpowDatasetItem.dagItem(cache, 2);
        assertEquals(256, item.length);
        for (int i = 0; i < 4; i++)
            assertArrayEquals(RvnKawpowDatasetItem.node(cache, 8 + i), Arrays.copyOfRange(item, i * 64, (i + 1) * 64));
    }

    @Test
    void fnv1AndFnv1aAreDistinct() {
        int a = 0x12345678, b = 0x87654321;
        assertEquals((a * RvnKawpowFnv.PRIME) ^ b, RvnKawpowFnv.fnv1(a, b));
        assertEquals((a ^ b) * RvnKawpowFnv.PRIME, RvnKawpowFnv.mix(a, b));
        assertNotEquals(RvnKawpowFnv.fnv1(a, b), RvnKawpowFnv.mix(a, b));
    }

    @Test
    void l1AndRoundsOnSyntheticDag() {
        RvnKawpowDagReader dag = new RvnKawpowDagReader() {
            public long itemCount() {
                return 64;
            }

            public byte[] item(long index) {
                byte[] bytes = new byte[256];
                for (int i = 0; i < 64; i++) RvnKawpowWords.writeLE((int) index * 64 + i, bytes, i * 4);
                return bytes;
            }
        };
        var l1 = RvnProgpowL1Cache.fromDag(dag);
        assertEquals(4096, l1.size());
        assertEquals(4095, l1.word(4095));
        var engine = new RvnProgpowRoundEngine(dag, l1);
        byte[] a = engine.calculate(30000, 123, 456);
        assertEquals(32, a.length);
        assertArrayEquals(a, engine.calculate(30000, 123, 456));
        assertFalse(Arrays.equals(a, engine.calculate(30003, 123, 456)));
    }

    @Test
    void rngPeriodAndInitialization() {
        var a = new RvnProgpowMixRng(3);
        var b = new RvnProgpowMixRng(5);
        for (int i = 0; i < 40; i++) {
            assertEquals(a.nextDst(), b.nextDst());
            assertEquals(a.nextSrc(), b.nextSrc());
            assertEquals(a.nextInt(), b.nextInt());
        }
        var init = RvnProgpowMixInit.initialize(1, 2);
        assertEquals(16, init.snapshot().length);
        assertNotEquals(init.get(0, 0), init.get(1, 0));
    }
}
