package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.protocol.transaction.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MempoolManagementTest {
    private static final byte[] SCRIPT = HexFormat.of().parseHex("0020" + HexFormat.of().formatHex(Sha256.hash(new byte[]{0x51})));
    private static final OutPoint FUND = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
    private static final UtxoView COINS = out -> out.equals(FUND) ? Optional.of(new UtxoEntry(100_000, SCRIPT, 100, false)) : Optional.empty();
    private static final MempoolValidationContext CONTEXT = new MempoolValidationContext(200, 1_700_000_000, h -> 1_600_000_000);

    @Test
    void fullRbfRemovesOriginalAndDescendantsAtomically() {
        var pool = new Mempool();
        var parent = tx(FUND, 90_000, 2);
        var child = tx(point(parent), 85_000, 2);
        pool.admit(parent, CONTEXT, COINS);
        pool.admit(child, CONTEXT, COINS);
        // More than the parent's fee, but less than the whole evicted package.
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(FUND, 88_000, 2), CONTEXT, COINS));
        assertTrue(pool.contains(parent.txId()));
        assertTrue(pool.contains(child.txId()));
        var replacement = tx(FUND, 80_000, 2);
        pool.admit(replacement, CONTEXT, COINS);
        assertEquals(1, pool.size());
        assertTrue(pool.contains(replacement.txId()));
        assertFalse(pool.contains(parent.txId()));
        assertFalse(pool.contains(child.txId()));
    }

    @Test
    void failedReplacementScriptLeavesOriginal() {
        var pool = new Mempool();
        var original = tx(FUND, 90_000, 2);
        pool.admit(original, CONTEXT, COINS);
        var replacement = tx(FUND, 80_000, 2);
        var bad = new Transaction(2, List.of(new TxIn(FUND, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0})))), replacement.outputs(), new UInt32(0));
        assertThrows(TransactionValidationException.class, () -> pool.admit(bad, CONTEXT, COINS));
        assertTrue(pool.contains(original.txId()));
    }

    @Test
    void dependencyLimitRejectsGrandchildWithoutChangingPool() {
        var limits = new MempoolLimits(2, 2, 101_000, 1_000_000, 1000, 100);
        var pool = new Mempool(new MempoolPolicy(), limits, Clock.systemUTC());
        var parent = tx(FUND, 90_000, 2);
        var child = tx(point(parent), 80_000, 2);
        pool.admit(parent, CONTEXT, COINS);
        pool.admit(child, CONTEXT, COINS);
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(point(child), 70_000, 2), CONTEXT, COINS));
        assertEquals(2, pool.size());
    }

    @Test
    void trucRejectsMixedVersionUnconfirmedDependencies() {
        var pool = new Mempool();
        var parent = tx(FUND, 90_000, 3);
        pool.admit(parent, CONTEXT, COINS);
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(point(parent), 80_000, 2), CONTEXT, COINS));
        var child = tx(point(parent), 80_000, 3);
        pool.admit(child, CONTEXT, COINS);
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(point(child), 70_000, 3), CONTEXT, COINS));
    }

    @Test
    void expiryRemovesDescendants() {
        class MutableClock extends Clock {
            Instant now = Instant.ofEpochSecond(1000);

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        }
        var clock = new MutableClock();
        var pool = new Mempool(new MempoolPolicy(), new MempoolLimits(25, 25, 101_000, 1_000_000, 100, 100), clock);
        var parent = tx(FUND, 90_000, 2);
        pool.admit(parent, CONTEXT, COINS);
        clock.now = Instant.ofEpochSecond(1050);
        pool.admit(tx(point(parent), 80_000, 2), CONTEXT, COINS);
        clock.now = Instant.ofEpochSecond(1101);
        assertEquals(2, pool.expire());
        assertTrue(pool.isEmpty());
    }

    @Test
    void restoredArrivalTimeControlsExpiryAfterRestart() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC);
        var pool = new Mempool(new MempoolPolicy(),
                new MempoolLimits(25, 25, 101_000, 1_000_000, 100, 100), clock);
        var transaction = tx(FUND, 90_000, 2);
        var restored = pool.admitRestored(transaction, 1_899L, CONTEXT, COINS);
        assertEquals(1_899L, restored.arrivalTime());
        assertEquals(1, pool.expire());
        assertTrue(pool.isEmpty());
    }

    @Test
    void capacityEvictsLowerFeePackageAndRejectsLowFeeArrival() {
        var first = tx(FUND, 90_000, 2);
        long size = TransactionWeight.virtualSize(TransactionWeight.calculate(first));
        var pool = new Mempool(new MempoolPolicy(), new MempoolLimits(25, 25, 101_000, size, 1000, 100), Clock.systemUTC());
        OutPoint other = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
        UtxoView coins = out -> Optional.of(new UtxoEntry(100_000, SCRIPT, 100, false));
        pool.admit(first, CONTEXT, coins);
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(other, 99_000, 2), CONTEXT, coins));
        assertTrue(pool.contains(first.txId()));
        var expensive = tx(other, 80_000, 2);
        pool.admit(expensive, CONTEXT, coins);
        assertEquals(1, pool.size());
        assertTrue(pool.contains(expensive.txId()));
    }

    private static OutPoint point(Transaction tx) {
        return new OutPoint(tx.txId(), new UInt32(0));
    }

    private static Transaction tx(OutPoint point, long value, int version) {
        return new Transaction(version, List.of(new TxIn(point, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))), List.of(new TxOut(value, SCRIPT)), new UInt32(0));
    }
}
