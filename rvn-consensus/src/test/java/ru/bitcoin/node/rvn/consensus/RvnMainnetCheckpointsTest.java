package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RvnMainnetCheckpointsTest {
    @Test void recoveryCheckpointUsesCoreDisplayOrderAndOnlyItsHeight() {
        assertEquals("000000000002d64509e06e76ddbbe418c725291687ec62b41ecfc40386a091fd", RvnMainnetCheckpoints.RECOVERY_ID.toString());
        assertDoesNotThrow(() -> RvnMainnetCheckpoints.require(4487775, RvnMainnetCheckpoints.RECOVERY_ID));
        var wrong = new RvnHash256(new byte[32]);
        assertThrows(SecurityException.class, () -> RvnMainnetCheckpoints.require(4487775, wrong));
        assertDoesNotThrow(() -> RvnMainnetCheckpoints.require(4487774, wrong));
        assertDoesNotThrow(() -> RvnMainnetCheckpoints.require(4487776, wrong));
    }
}
