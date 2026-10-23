package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RvnProgpowStateTest {
    @Test
    void stateIsDefensiveAndExecutesOperations() {
        int[][] initial = new int[16][32];
        initial[0][1] = 3;
        var state = new RvnProgpowLaneState(initial);
        initial[0][1] = 100;
        assertEquals(3, state.get(0, 1));
        state.set(0, 0, 7);
        state.math(0, 0, 1, 0);
        assertEquals(10, state.get(0, 0));
        state.merge(0, 0, 5, 0);
        assertEquals(335, state.get(0, 0));
        var snapshot = state.snapshot();
        snapshot[0][0] = 999;
        assertEquals(335, state.get(0, 0));
        assertEquals(32, state.reducedMix().length);
        assertThrows(IndexOutOfBoundsException.class, () -> state.get(16, 0));
    }

    @Test
    void deterministicTestProgramAndBounds() {
        var a = RvnProgpowProgramGenerator.generate(7, 32);
        var b = RvnProgpowProgramGenerator.generate(7, 32);
        int[][] zeros = new int[16][32];
        var left = new RvnProgpowLaneState(zeros);
        var right = new RvnProgpowLaneState(zeros);
        int[] values = new int[32];
        Arrays.fill(values, 0x12345678);
        a.execute(left, 0, values);
        b.execute(right, 0, values);
        assertArrayEquals(left.snapshot()[0], right.snapshot()[0]);
        assertThrows(IllegalArgumentException.class, () -> a.execute(left, 0, new int[31]));
        assertThrows(IllegalArgumentException.class, () -> RvnProgpowProgramGenerator.generate(1, 0));
    }

    @Test
    void dagWordsAreDefensiveAndUnsigned() {
        byte[] data = new byte[256];
        RvnKawpowWords.writeLE(0x89abcdef, data, 124);
        var dag = new RvnKawpowDagReader() {
            public long itemCount() {
                return 2;
            }

            public byte[] item(long index) {
                return data;
            }
        };
        var access = new RvnProgpowDagAccess(dag);
        assertEquals(1, access.normalizedIndex(-1));
        assertEquals(0x89abcdef, access.word(1, 31));
        int[] words = access.words(0);
        words[31] = 0;
        assertEquals(0x89abcdef, access.word(0, 31));
        assertEquals(0, access.word(0, 32));
        assertThrows(IndexOutOfBoundsException.class, () -> access.word(0, 64));
    }
}
