package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RvnProgpowMathTest {
    @Test
    void allElevenOperationsAndUnsignedEdges() {
        assertEquals(-2, RvnProgpowMath.apply(-1, -1, 0));
        assertEquals(1, RvnProgpowMath.apply(-1, -1, 1));
        assertEquals(-2, RvnProgpowMath.apply(-1, -1, 2));
        assertEquals(1, RvnProgpowMath.apply(-1, 1, 3));
        assertEquals(2, RvnProgpowMath.apply(1, 1, 4));
        assertEquals(0x80000000, RvnProgpowMath.apply(1, 1, 5));
        assertEquals(1, RvnProgpowMath.apply(1, 1, 6));
        assertEquals(1, RvnProgpowMath.apply(1, 1, 7));
        assertEquals(0, RvnProgpowMath.apply(1, 1, 8));
        assertEquals(62, RvnProgpowMath.apply(1, 1, 9));
        assertEquals(2, RvnProgpowMath.apply(1, 1, 10));
    }

    @Test
    void selectorWrapsModuloEleven() {
        for (int s = 0; s < 11; s++) assertEquals(RvnProgpowMath.apply(7, 13, s), RvnProgpowMath.apply(7, 13, s + 11));
    }
}
