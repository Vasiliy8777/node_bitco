package ru.bitcoin.node.app.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PruneConfigurationTest {
    @Test
    void pruningIsDisabledAtZero() {
        assertEquals(0L, NodeConfiguration.pruneTargetBytes(0));
    }

    @Test
    void coreCompatibleManualModeAndAutomaticMinimumAreEnforced() {
        assertEquals(ru.bitcoin.node.chain.BlockPruner.MANUAL_ONLY, NodeConfiguration.pruneTargetBytes(1));
        assertThrows(IllegalArgumentException.class, () -> NodeConfiguration.pruneTargetBytes(2));
        assertThrows(IllegalArgumentException.class, () -> NodeConfiguration.pruneTargetBytes(549));
        assertEquals(550L * 1024L * 1024L, NodeConfiguration.pruneTargetBytes(550));
    }
}
