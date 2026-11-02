package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionLimitsTest {
    @Test void coreDefaultsReserveHalfInboundForBlockOnlyPeers() {
        var limits = new ConnectionLimits(200, 8, 2, 50);
        assertEquals(190, limits.inbound());
        assertEquals(95, limits.inboundFullRelay());
        assertEquals(115, new ConnectionLimits(125, 8, 2, 100).inboundFullRelay());
    }
    @Test void clampsTotalAndAllocatesSmallBudgetsWithoutNegativeCounts() {
        assertEquals(2000, new ConnectionLimits(10000, 8, 2, 50).maximum());
        assertEquals(new ConnectionLimits(0, 0, 0, 0), new ConnectionLimits(0, 8, 2, 0));
        assertEquals(0, new ConnectionLimits(5, 8, 2, 50).inbound());
        assertEquals(0, new ConnectionLimits(9, 8, 2, 50).inbound());
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(200, 8, 2, 101));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(-1, 8, 2, 50));
    }
}
