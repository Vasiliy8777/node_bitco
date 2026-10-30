package ru.bitcoin.node.common;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NoncePermutationTest {
    @Test void inverseRecoversCounterAcrossSignedAndUnsignedBoundaries() {
        long[] boundaries = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0xffff_ffffL, 0x1_0000_0000L};
        for (long key : boundaries) for (long counter : boundaries) {
            assertEquals(counter, NoncePermutation.inverse64(NoncePermutation.permute64(counter, key), key));
            assertEquals((int) counter, NoncePermutation.inverse32(NoncePermutation.permute32((int) counter, (int) key), (int) key));
        }
        var random = new Random(123456);
        for (int i = 0; i < 100_000; i++) {
            long key = random.nextLong(), counter = random.nextLong();
            assertEquals(counter, NoncePermutation.inverse64(NoncePermutation.permute64(counter, key), key));
            assertEquals((int) counter, NoncePermutation.inverse32(NoncePermutation.permute32((int) counter, (int) key), (int) key));
        }
    }

    @Test void separateLogicalRangesDoNotIntersectAndExtraNonceStopsBeforeWrap() {
        var seen = new HashSet<Long>();
        for (int worker = 0; worker < 4; worker++) for (int i = 0; i < 32768; i++)
            assertTrue(seen.add(NoncePermutation.permute64(worker * 32768L + i, 0xfedcba9876543210L)));
        var sequence = new ExtraNonceSequence(1234, -2);
        assertNotEquals(sequence.next(), sequence.next());
        assertThrows(IllegalStateException.class, sequence::next);
        var concurrent = new ExtraNonceSequence(5678, 0);
        assertEquals(10000, java.util.stream.IntStream.range(0, 10000).parallel().mapToObj(i -> concurrent.next()).distinct().count());
    }
}
