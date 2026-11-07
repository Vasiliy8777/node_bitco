package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class SubmissionWitnessDedupTest {
    @Test void singleSubmissionPreflightRecognizesExactKnownEntry() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        var known = pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        var result = pool.testSubmitPackage(List.of(parent), CONTEXT, COINS);
        assertTrue(result.allowed(), result.rejectReason()); assertEquals(List.of(known), result.entries());
        assertEquals(Set.of(parent.txId()), result.knownSubmissionTxids());
        assertEquals(before, pool.snapshot());
    }
    @Test void alternativeWitnessReturnsStoredEntryWithoutReplacement() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        var known = pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        var changed = changeWitness(parent);
        var preflight = pool.testSubmitPackage(List.of(changed), CONTEXT, COINS);
        assertTrue(preflight.allowed(), preflight.rejectReason()); assertEquals(List.of(known), preflight.entries());
        assertEquals(Set.of(parent.txId()), preflight.knownSubmissionTxids());
        assertEquals(List.of(known), pool.admitPackage(List.of(changed), CONTEXT, COINS));
        assertEquals(before, pool.snapshot());
        assertFalse(pool.testAccept(List.of(changed), CONTEXT, COINS).allowed());
    }
    @Test void alternativeParentWitnessDoesNotPreventNewChild() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        var changed = changeWitness(parent); var child = tx(List.of(point(parent, 0)), 2, 80000);
        var preflight = pool.testSubmitPackage(List.of(changed, child), CONTEXT, COINS);
        assertTrue(preflight.allowed(), preflight.rejectReason()); assertEquals(before, pool.snapshot());
        assertEquals(Set.of(parent.txId()), preflight.knownSubmissionTxids());
        assertFalse(preflight.knownSubmissionTxids().contains(child.txId()));
        var accepted = pool.admitPackage(List.of(changed, child), CONTEXT, COINS);
        assertEquals(parent.wtxId(), accepted.getFirst().transaction().wtxId());
        assertEquals(child.wtxId(), accepted.getLast().transaction().wtxId()); assertEquals(2, pool.size());
    }
    private static Transaction changeWitness(Transaction tx) {
        var first = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(first.previousOutput(), first.scriptSig(), first.sequence(),
                new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}
