package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnForkRecoveryTest {
    @TempDir
    Path temp;
    private static final RvnHeaderFormatPolicy FORMAT = new RvnHeaderFormatPolicy(0);

    private static RvnHeaderCheckpoint checkpoint() {
        return new RvnHeaderCheckpoint(id(90), 0, 0, BigInteger.ZERO, CompactTarget.decode(BITS));
    }

    private static RvnHeaderRecoveryManager manager(
            RvnValidatedHeaderIndex.ContextualVerifierFactory factory) {
        return new RvnHeaderRecoveryManager(checkpoint(), FORMAT, factory, 100);
    }

    @Test
    void forkedJournalRestoresBothBranchesAndBestTip() throws Exception {
        var index = checkpoint().newIndex(100);
        index.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)), TEST_ONLY, FORMAT);
        index.admitBatchAtomic(List.of(item(3, id(1), 2), item(4, id(3), 3)), TEST_ONLY, FORMAT);
        Path file = temp.resolve("forks.bin");
        manager(staged -> TEST_ONLY).save(file, index);
        var result = manager(staged -> TEST_ONLY).restore(file);
        assertEquals(4, result.restored());
        assertEquals(4, result.index().size());
        assertTrue(result.index().find(id(2)).isPresent());
        assertTrue(result.index().find(id(4)).isPresent());
        assertEquals(id(4), result.index().bestId());
        assertEquals(3, result.index().bestHeight());
        assertEquals(id(1), result.index().commonAncestor(id(2), id(4)));
    }

    @Test
    void contextualFactorySeesEarlierHeadersOfSameRun() throws Exception {
        var index = checkpoint().newIndex(100);
        index.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)), TEST_ONLY, FORMAT);
        Path file = temp.resolve("linear.bin");
        RvnHeaderRecovery.save(file, index);
        var result = manager(staged -> new RvnHeaderProofGate.Verifier() {
            public boolean blockIdMatches(RvnHeaderCandidate c, byte[] b) {
                return true;
            }

            public boolean validProofOfWork(RvnHeaderCandidate c, byte[] b) {
                return true;
            }

            public boolean validContext(RvnHeaderCandidate c) {
                return c.parentId().equals(id(90)) || staged.find(c.parentId()).isPresent();
            }
        }).restore(file);
        assertEquals(2, result.restored());
    }

    @Test
    void duplicatedJournalIdIsRejected() throws Exception {
        Path file = temp.resolve("duplicate.bin");
        var record = new RvnVerifiedHeaderJournal.Record(id(1), raw(id(90), 1));
        RvnVerifiedHeaderJournal.writeAtomically(file, List.of(record, record));
        assertThrows(SecurityException.class, () -> manager(staged -> TEST_ONLY).restore(file));
    }

    @Test
    void corruptedJournalFailsBeforeReturningIndex() throws Exception {
        Path file = temp.resolve("corrupt.bin");
        RvnVerifiedHeaderJournal.writeAtomically(file, List.of(
                new RvnVerifiedHeaderJournal.Record(id(1), raw(id(90), 1))));
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        assertThrows(java.io.IOException.class, () -> manager(staged -> TEST_ONLY).restore(file));
    }
}
