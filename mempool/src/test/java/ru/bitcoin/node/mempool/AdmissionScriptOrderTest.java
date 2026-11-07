package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class AdmissionScriptOrderTest {
    @Test void lowFeeRejectsBeforeInvalidWitnessExecution() {
        var pool = new Mempool(); var bad = invalidWitness(tx(List.of(FUND), 2, 100000));
        var error = assertThrows(MempoolAdmissionException.class, () -> pool.admit(bad, CONTEXT, COINS));
        assertTrue(error.getMessage().startsWith("Minimum relay fee not met")); assertTrue(pool.isEmpty());
    }
    @Test void insufficientReplacementFeeRejectsBeforeInvalidWitnessExecution() {
        var pool = new Mempool(); var original = tx(List.of(FUND), 2, 90000);
        pool.admit(original, CONTEXT, COINS); var before = pool.snapshot();
        var bad = invalidWitness(tx(List.of(FUND), 2, 90000));
        // Change non-witness data so this is an RBF candidate, rather than an already-known txid.
        bad = new Transaction(bad.version(), bad.inputs(), bad.outputs(), new ru.bitcoin.node.common.types.UInt32(1));
        var candidate = bad;
        var error = assertThrows(MempoolAdmissionException.class, () -> pool.admit(candidate, CONTEXT, COINS));
        assertEquals("replacement-fee", error.getMessage()); assertEquals(before, pool.snapshot());
    }
    @Test void sufficientFeeStillRequiresValidScriptsBeforeAnyCommit() {
        var pool = new Mempool(); var before = pool.snapshot();
        var bad = invalidWitness(tx(List.of(FUND), 2, 90000));
        assertThrows(ru.bitcoin.node.consensus.transaction.TransactionValidationException.class, () -> pool.admit(bad, CONTEXT, COINS));
        assertEquals(before, pool.snapshot());
        assertThrows(ru.bitcoin.node.consensus.transaction.TransactionValidationException.class, () -> MempoolValidator.validate(bad, CONTEXT, COINS));
    }
    private static Transaction invalidWitness(Transaction tx) {
        var input = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}
