package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MempoolTestAcceptTest {
    private static final byte[] SCRIPT = HexFormat.of().parseHex("0020" + HexFormat.of().formatHex(Sha256.hash(new byte[]{0x51})));
    private static final OutPoint FUND = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
    private static final UtxoView COINS = out -> out.equals(FUND) ? Optional.of(new UtxoEntry(100_000, SCRIPT, 100, false)) : Optional.empty();
    private static final MempoolValidationContext CONTEXT = new MempoolValidationContext(200, 1_700_000_000, h -> 1_600_000_000);

    @Test
    void dryRunDoesNotMutatePoolOnSuccessOrFailure() {
        Mempool pool = new Mempool();
        Transaction tx = tx(FUND, 90_000);
        var accepted = pool.testAccept(List.of(tx), CONTEXT, COINS);
        assertTrue(accepted.allowed());
        assertEquals(1, accepted.entries().size());
        assertEquals(0, pool.size());

        Transaction missing = tx(new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0)), 1_000);
        var rejected = pool.testAccept(List.of(missing), CONTEXT, COINS);
        assertFalse(rejected.allowed());
        assertEquals(0, pool.size());
    }

    @Test
    void dryRunUsesLiveRollingMinimumFeeFloor() {
        Mempool pool = new Mempool(new MempoolPolicy(),
                new MempoolLimits(64, 101_000, 1, 1000, 100), java.time.Clock.systemUTC());
        Transaction evicted = tx(FUND, 90_000);
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(evicted));
        assertTrue(pool.minimumFeeRate() > pool.minimumRelayFeeRate());

        Transaction lowFee = tx(FUND, 99_900);
        var rejected = pool.testAccept(List.of(lowFee), CONTEXT, COINS);
        assertFalse(rejected.allowed());
        assertEquals("mempool-min-fee", rejected.rejectReason());
        assertEquals(0, pool.size());
    }

    @Test
    void packageDryRunMustNotPerformPackageRbf() {
        Mempool pool = new Mempool();
        Transaction original = tx(FUND, 90_000);
        pool.admit(original, CONTEXT, COINS);

        Transaction parent = tx(FUND, 100_000);
        Transaction child = tx(new OutPoint(parent.txId(), new UInt32(0)), 70_000);

        // submitpackage may use the constrained v31 package-RBF path...
        Mempool submitting = new Mempool();
        submitting.admit(original, CONTEXT, COINS);
        assertEquals(2, submitting.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
        assertFalse(submitting.contains(original.txId()));

        // ...but PackageTestAccept in Core v31 has allow_replacement=false.
        var probe = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertFalse(probe.allowed());
        assertEquals("txn-mempool-conflict", probe.rejectReason());
        assertTrue(pool.contains(original.txId()));
        assertEquals(1, pool.size());
    }

    private static Transaction tx(OutPoint point, long value) {
        return new Transaction(2, List.of(new TxIn(point, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))), List.of(new TxOut(value, SCRIPT)), new UInt32(0));
    }
}
