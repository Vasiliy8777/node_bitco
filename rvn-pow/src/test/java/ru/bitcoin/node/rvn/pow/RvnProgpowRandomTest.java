package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RvnProgpowRandomTest {
    @Test
    void deterministicAndStateful() {
        var a = new RvnProgpowRandom(1, 2, 3, 4);
        var b = new RvnProgpowRandom(1, 2, 3, 4);
        for (int i = 0; i < 1000; i++) assertEquals(a.nextInt(), b.nextInt());
        assertArrayEquals(a.snapshot(), b.snapshot());
    }

    @Test
    void permutationIsValidAndRepeatable() {
        int[] a = RvnProgpowPermutation.shuffled(32, new RvnProgpowRandom(1, 2, 3, 4));
        int[] b = RvnProgpowPermutation.shuffled(32, new RvnProgpowRandom(1, 2, 3, 4));
        assertArrayEquals(a, b);
        RvnProgpowPermutation.requirePermutation(a, 32);
        assertThrows(IllegalArgumentException.class, () -> RvnProgpowPermutation.requirePermutation(new int[32], 32));
        assertThrows(IllegalArgumentException.class, () -> RvnProgpowPermutation.shuffled(0, new RvnProgpowRandom(1, 2, 3, 4)));
    }
}
