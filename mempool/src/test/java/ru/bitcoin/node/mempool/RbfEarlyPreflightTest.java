package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class RbfEarlyPreflightTest {
    @Test void oversizedSingleReplacementStopsBeforeLoadingCoins() {
        var f = fixture();
        var before = f.pool().snapshot();
        var reads = new AtomicInteger();
        var replacement = tx(f.funds(), 2, 1000000);
        var error = assertThrows(MempoolAdmissionException.class,
                () -> f.pool().admit(replacement, CONTEXT, blockedCoins(reads)));
        assertEquals("too-many-conflicting-clusters", error.getMessage());
        assertEquals(0, reads.get());
        assertEquals(before, f.pool().snapshot());
    }

    @Test void oversizedPackageStopsBeforeLoadingCoinsOrStagingAdmission() {
        var f = fixture();
        var before = f.pool().snapshot();
        var reads = new AtomicInteger();
        var parent = tx(f.funds(), 2, 10100000);
        var child = tx(List.of(point(parent, 0)), 2, 1000000);
        var error = assertThrows(MempoolAdmissionException.class,
                () -> f.pool().admitPackage(List.of(parent, child), CONTEXT, blockedCoins(reads)));
        assertEquals("package-rbf-too-many-conflicts", error.getMessage());
        assertEquals(0, reads.get());
        assertEquals(before, f.pool().snapshot());
    }

    private static UtxoView blockedCoins(AtomicInteger reads) {
        return point -> { reads.incrementAndGet(); throw new AssertionError("UTXO lookup after excessive cluster count"); };
    }
    private record Fixture(Mempool pool, List<OutPoint> funds) {}
    private static Fixture fixture() {
        var funds = new ArrayList<OutPoint>();
        for (int i = 1; i <= 101; i++) funds.add(new OutPoint(Hash256.fromDisplayHex(String.format("%064x", i)), new UInt32(0)));
        var known = new HashSet<>(funds);
        UtxoView coins = point -> known.contains(point) ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : Optional.empty();
        var pool = new Mempool();
        for (var fund : funds) pool.admit(tx(List.of(fund), 2, 99900), CONTEXT, coins);
        return new Fixture(pool, funds);
    }
}
