package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompactTargetTest {
    @Test void bitcoinFamilyReferenceTarget() {
        assertEquals(new BigInteger("ffff0000000000000000000000000000000000000000000000000000", 16), CompactTarget.decode(0x1d00ffffL));
    }
    @Test void rejectInvalidTargets() {
        assertThrows(IllegalArgumentException.class, () -> CompactTarget.decode(0));
        assertThrows(IllegalArgumentException.class, () -> CompactTarget.decode(0x1d80ffffL));
        assertThrows(IllegalArgumentException.class, () -> CompactTarget.decode(0x23010000L));
        assertThrows(IllegalArgumentException.class, () -> CompactTarget.decode(0x100000000L));
    }
    @Test void inclusiveComparison() {
        byte[] zero = new byte[32];
        assertTrue(CompactTarget.meetsTarget(zero, 0x1d00ffffL));
        byte[] max = new byte[32]; java.util.Arrays.fill(max, (byte) 0xff);
        assertFalse(CompactTarget.meetsTarget(max, 0x1d00ffffL));
    }
}
