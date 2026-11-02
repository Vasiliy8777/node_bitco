package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionRelayBucketsTest {
    @Test void initialBurstAndDirectionalRefillMatchCore() {
        var buckets = new TransactionRelayBuckets(14);
        var transactions = transactions(2000, 100);
        transactions.forEach(tx -> buckets.add(tx.wtxid()));
        var first = buckets.drain(0, transactions, true, true);
        assertEquals(490, first.inbound().size());
        assertEquals(490, first.outbound().size());
        assertTrue(buckets.drain(2_000_000_000L, transactions, true, true).inbound().isEmpty());
        var later = buckets.drain(6_000_000_000L, transactions, true, true);
        assertEquals(84, later.inbound().size());
        assertTrue(later.outbound().size() > later.inbound().size());
    }

    @Test void bandwidthDebtStopsLargeTransactionsAndRefillsAtCoreRate() {
        var buckets = new TransactionRelayBuckets(1_000_000);
        var transactions = transactions(100, 1_000_000);
        transactions.forEach(tx -> buckets.add(tx.wtxid()));
        assertEquals(13, buckets.drain(0, transactions, true, true).inbound().size());
        assertFalse(buckets.ready(1_000_000_000L));
        var later = buckets.drain(60_000_000_000L, transactions, true, true);
        assertEquals(1, later.inbound().size());
        assertEquals(3, later.outbound().size());
    }

    @Test void deduplicatesUsesMiningOrderAndDropsEvictedTransactions() {
        var buckets = new TransactionRelayBuckets(14);
        var transactions = transactions(4, 100);
        buckets.add(transactions.get(3).wtxid());
        buckets.add(transactions.get(1).wtxid());
        buckets.add(transactions.get(1).wtxid());
        buckets.add(transactions.get(0).wtxid());
        var selected = buckets.drain(0, transactions.subList(1, 4), true, false);
        assertEquals(List.of(transactions.get(1), transactions.get(3)), selected.inbound());
        assertTrue(selected.outbound().isEmpty());
        var info = buckets.info();
        assertEquals(0, ((Map<?, ?>) info.get("inbound")).get("backlog"));
        assertEquals(0, ((Map<?, ?>) info.get("outbound")).get("backlog"));
    }

    private static List<TransactionRelayBuckets.Transaction> transactions(int count, int bytes) {
        var result = new ArrayList<TransactionRelayBuckets.Transaction>();
        for (int i = 0; i < count; i++) {
            byte[] raw = new byte[32]; java.nio.ByteBuffer.wrap(raw).putInt(i);
            var id = new Hash256(raw);
            result.add(new TransactionRelayBuckets.Transaction(id, id, 1000, bytes));
        }
        return result;
    }
}
