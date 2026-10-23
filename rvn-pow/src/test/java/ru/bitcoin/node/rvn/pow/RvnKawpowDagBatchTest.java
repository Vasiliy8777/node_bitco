package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowDagBatchTest {
    @Test
    void parallelBatchPreservesOrderAndDuplicates() {
        var dag = new RvnKawpowLightDag(RvnKawpowCache.build(new byte[32], 17), 256L * 32, 4);
        var batch = new RvnKawpowDagBatch(dag);
        long[] indexes = {3, 0, 3, 1, 7, 0, 5};
        List<byte[]> result = batch.read(indexes, 4);
        assertEquals(indexes.length, result.size());
        for (int i = 0; i < indexes.length; i++) assertArrayEquals(dag.item(indexes[i]), result.get(i));
        result.get(0)[0] ^= 0x33;
        assertArrayEquals(dag.item(3), result.get(2));
        assertFalse(Arrays.equals(result.get(0), dag.item(3)));
    }

    @Test
    void emptyBatchAndInvalidParameters() {
        var dag = new RvnKawpowLightDag(RvnKawpowCache.build(new byte[32], 17), 256L * 4, 0);
        var batch = new RvnKawpowDagBatch(dag);
        assertEquals(List.of(), batch.read(new long[0], 1));
        assertThrows(IllegalArgumentException.class, () -> batch.read(null, 1));
        assertThrows(IllegalArgumentException.class, () -> batch.read(new long[]{0}, 0));
        assertThrows(IllegalArgumentException.class, () -> batch.read(new long[]{0}, 65));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.read(new long[]{4}, 2));
    }

    @Test
    void providerFailureIsPropagated() {
        RvnKawpowDagReader reader = new RvnKawpowDagReader() {
            public long itemCount() {
                return 5;
            }

            public byte[] item(long i) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        assertThrows(IllegalStateException.class, () -> new RvnKawpowDagBatch(reader).read(new long[]{1}, 2));
    }
}
