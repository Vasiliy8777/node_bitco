package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnStages24to27Test {
    @TempDir
    Path temp;
    private static final RvnHeaderFormatPolicy FORMAT = new RvnHeaderFormatPolicy(0);
    private static final RvnHeaderCheckpoint CHECKPOINT = new RvnHeaderCheckpoint(
            id(90), 0, 0, BigInteger.ZERO, CompactTarget.decode(BITS));

    @Test
    void contextualRecoveryRechecksAndDoesNotLeakOnFailure() throws Exception {
        var original = CHECKPOINT.newIndex(20);
        original.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)),
                TEST_ONLY, FORMAT);
        Path journal = temp.resolve("headers.bin");
        RvnHeaderRecovery.save(journal, original);
        var denied = new RvnHeaderRecoveryManager(CHECKPOINT, FORMAT,
                staged -> RvnHeaderProofGate.unavailable(), 20);
        assertThrows(SecurityException.class, () -> denied.restore(journal));
        var allowed = new RvnHeaderRecoveryManager(CHECKPOINT, FORMAT,
                staged -> TEST_ONLY, 20);
        var restored = allowed.restore(journal);
        assertEquals(2, restored.restored());
        assertEquals(id(2), restored.index().bestId());
        allowed.save(temp.resolve("again.bin"), restored.index());
        assertEquals(2, RvnVerifiedHeaderJournal.read(temp.resolve("again.bin")).size());
    }

    @Test
    void digestByteOrderIsExplicitAndInclusive() {
        byte[] digest = new byte[32];
        digest[0] = (byte) 0x80;
        BigInteger powLimit = CompactTarget.decode(BITS);
        assertTrue(RvnPowDigest.meetsTarget(digest, RvnPowDigest.ByteOrder.LITTLE_ENDIAN, BITS, powLimit));
        assertFalse(RvnPowDigest.meetsTarget(digest, RvnPowDigest.ByteOrder.BIG_ENDIAN, BITS, powLimit));
        assertThrows(IllegalArgumentException.class, () -> RvnPowDigest.unsignedValue(new byte[31],
                RvnPowDigest.ByteOrder.BIG_ENDIAN));
        byte[] zero = new byte[32];
        assertTrue(RvnPowDigest.meetsTarget(zero, RvnPowDigest.ByteOrder.BIG_ENDIAN, BITS, powLimit));
    }

    @Test
    void checkpointRejectsInvalidAndAuditDetectsWrongAnchor() {
        assertThrows(IllegalArgumentException.class, () -> new RvnHeaderCheckpoint(
                id(90), -1, 0, BigInteger.ZERO, CompactTarget.decode(BITS)));
        var wrong = new RvnHeaderCheckpoint(id(91), 0, 0, BigInteger.ZERO, CompactTarget.decode(BITS));
        var index = CHECKPOINT.newIndex(10);
        assertThrows(SecurityException.class, () -> wrong.audit(index));
    }
}
