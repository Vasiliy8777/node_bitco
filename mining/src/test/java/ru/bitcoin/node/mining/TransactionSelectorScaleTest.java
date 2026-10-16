package ru.bitcoin.node.mining;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionSelectorScaleTest {
    private static Transaction tx(int tag) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)),
                new UInt32(tag)), new byte[0], TxIn.FINAL_SEQUENCE)), List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }

    @Test
    @Timeout(30)
    void largeIndependentPoolIsSelectedInFeeOrder() {
        var entries = new ArrayList<MempoolEntry>();
        for (int i = 0; i < 10000; i++) entries.add(new MempoolEntry(tx(i), i + 1, 400, 0, 0));
        long started = System.nanoTime();
        var selected = TransactionSelector.select(entries, 4_000_000, 80_000, new FeeRate(0));
        assertEquals(entries.size(), selected.size());
        for (int i = 0; i < selected.size(); i++)
            assertSame(entries.get(entries.size() - 1 - i).transaction(), selected.get(i));
        System.out.printf(Locale.ROOT, "TEMPLATE_SCALE transactions=%d elapsedMs=%.1f%n",
                selected.size(), (System.nanoTime() - started) / 1_000_000.0);
    }

    @Test
    void equalRatesKeepSnapshotOrderAndOversizedBestDoesNotBlockOtherClusters() {
        var a = tx(1);
        var b = tx(2);
        var c = tx(3);
        var entries = List.of(new MempoolEntry(a, 1000, 400, 0, 0), new MempoolEntry(b, 1000, 400, 0, 0),
                new MempoolEntry(c, 10000, 800, 0, 0));
        assertEquals(List.of(a), TransactionSelector.select(entries, 400, 0, new FeeRate(0)));
        assertEquals(List.of(c, a, b), TransactionSelector.select(entries, 1600, 0, new FeeRate(0)));
    }
}
