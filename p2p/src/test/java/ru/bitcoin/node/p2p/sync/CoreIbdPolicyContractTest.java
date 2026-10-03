package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreIbdPolicyContractTest {
    @Test
    void usesCorePerPeerTransitLimit() {
        assertEquals(16, BlockInFlightTracker.DEFAULT_MAX_BLOCKS_PER_PEER);
    }
}
