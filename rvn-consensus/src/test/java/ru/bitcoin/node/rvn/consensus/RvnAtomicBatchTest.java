package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnAtomicBatchTest {
    static final RvnHeaderFormatPolicy POLICY = new RvnHeaderFormatPolicy(0);

    @Test
    void commitsWholeChainAndExportsParentFirst() {
        var index = index(10);
        var items = List.of(item(1, id(90), 1), item(2, id(1), 2), item(3, id(2), 3));
        var result = index.admitBatchAtomic(items, TEST_ONLY, POLICY);
        assertEquals(3, result.size());
        assertEquals(3, index.size());
        assertEquals(id(3), index.bestId());
        assertEquals(List.of(id(1), id(2), id(3)),
                index.journalRecords().stream().map(RvnVerifiedHeaderJournal.Record::claimedId).toList());
    }

    @Test
    void failedProofDoesNotPartiallyCommit() {
        var index = index(10);
        var items = List.of(item(1, id(90), 1), item(2, id(1), 2));
        var verifier = new RvnHeaderProofGate.Verifier() {
            public boolean blockIdMatches(RvnHeaderCandidate c, byte[] b) {
                return !c.id().equals(id(2));
            }

            public boolean validProofOfWork(RvnHeaderCandidate c, byte[] b) {
                return true;
            }

            public boolean validContext(RvnHeaderCandidate c) {
                return true;
            }
        };
        assertThrows(SecurityException.class, () -> index.admitBatchAtomic(items, verifier, POLICY));
        assertEquals(0, index.size());
        assertEquals(id(90), index.bestId());
    }

    @Test
    void capacityFailureDoesNotPartiallyCommit() {
        var index = index(1);
        assertThrows(IllegalStateException.class, () -> index.admitBatchAtomic(
                List.of(item(1, id(90), 1), item(2, id(1), 2)), TEST_ONLY, POLICY));
        assertEquals(0, index.size());
    }

    @Test
    void failedSecondBatchPreservesFirstBatch() {
        var index = index(10);
        index.admitBatchAtomic(List.of(item(1, id(90), 1)), TEST_ONLY, POLICY);
        assertThrows(SecurityException.class, () -> index.admitBatchAtomic(
                List.of(item(2, id(1), 2), item(3, id(2), 4)), TEST_ONLY, POLICY));
        assertEquals(1, index.size());
        assertEquals(id(1), index.bestId());
    }
}
