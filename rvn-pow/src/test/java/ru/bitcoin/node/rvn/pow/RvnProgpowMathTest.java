package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RvnProgpowMathTest {
    @Test
    void operationsAndUnsignedEdges() {
        assertEquals(-2, RvnProgpowMath.apply(-1, -1, 0));
        assertEquals(1, RvnProgpowMath.apply(-1, -1, 1));
        assertEquals(-2, RvnProgpowMath.apply(-1, -1, 2));
        assertEquals(1, RvnProgpowMath.apply(-1, 1, 3));
        assertEquals(-1, RvnProgpowMath.apply(-1, 1, 4));
        assertEquals(2, RvnProgpowMath.apply(1, 1, 5));
        assertEquals(0x80000000, RvnProgpowMath.apply(1, 1, 6));
        assertEquals(64, RvnProgpowMath.apply(-1, -1, 7));
    }

    @Test
    void selectorWrapsModuloEight() {
        for (int s = 0; s < 8; s++) assertEquals(RvnProgpowMath.apply(7, 13, s), RvnProgpowMath.apply(7, 13, s + 8));
    }
}
