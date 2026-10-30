package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowSharedCacheTest {
    @Test void preparedImmutableEpochCacheMatchesCoreAndRejectsWrongProvenance() {
        var cache = RvnKawpowCache.forEpoch(0);
        var engine = new RvnKawpowLightEngine(65536);
        assertThrows(IllegalArgumentException.class, () -> engine.prepareEpoch(1, cache));
        assertThrows(IllegalArgumentException.class, () -> engine.prepareEpoch(0, RvnKawpowCache.build(new byte[32], 17)));
        byte[] original = cache.row(0), modified = cache.row(0); modified[0] ^= 1;
        assertArrayEquals(original, cache.row(0));
        engine.prepareEpoch(0, cache);
        RvnKawpowSelfTest.requireValid(engine);
        var serial = new RvnKawpowLightEngine(1024);
        var expected = serial.compute(1, new byte[32], -1L);
        var actual = engine.compute(1, new byte[32], -1L);
        assertArrayEquals(expected.mixHash(), actual.mixHash());
        assertArrayEquals(expected.finalHash(), actual.finalHash());
    }
}
