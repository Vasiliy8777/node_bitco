package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowDagPageAndSelfCheckTest {
    @Test
    void pageReadsAreDefensiveAndLittleEndian() {
        var cache = RvnKawpowCache.build(new byte[32], 17);
        var dag = new RvnKawpowLightDag(cache, 256L * 8, 2);
        var page = new RvnKawpowDagPage(dag, 2, 3);
        assertEquals(2, page.firstIndex());
        assertEquals(3, page.size());
        for (int i = 0; i < 3; i++) {
            byte[] item = page.itemAt(i);
            assertArrayEquals(dag.item(i + 2), item);
            assertEquals(RvnKawpowWords.readLE(item, 0), page.wordLE(i, 0));
            assertArrayEquals(Arrays.copyOfRange(item, 0, 4), page.wordBytes(i, 0));
            item[0] ^= 0x66;
            assertFalse(Arrays.equals(item, page.itemAt(i)));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> page.wordLE(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new RvnKawpowDagPage(dag, 7, 2));
        assertThrows(IllegalArgumentException.class, () -> new RvnKawpowDagPage(dag, 0, 0));
    }

    @Test
    void selfCheckDetectsCorruption() {
        var cache = RvnKawpowCache.build(new byte[32], 17);
        var dag = new RvnKawpowLightDag(cache, 256L * 8, 2);
        RvnKawpowDagSelfCheck.verify(dag, cache, 0, 2, 7);
        RvnKawpowDagReader broken = new RvnKawpowDagReader() {
            public long itemCount() {
                return 8;
            }

            public byte[] item(long index) {
                return new byte[256];
            }
        };
        assertThrows(IllegalStateException.class, () -> RvnKawpowDagSelfCheck.verify(broken, cache, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> RvnKawpowDagSelfCheck.verify(dag, cache, 8));
    }
}
