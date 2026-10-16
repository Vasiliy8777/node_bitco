package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;

import java.lang.management.ManagementFactory;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MempoolRevalidationScaleTest {
    @Test
    void revalidationDoesNotAllocateAWholePoolCopyPerTransaction() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        var allocations = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        byte[] script = java.util.HexFormat.of().parseHex("0020" +
                java.util.HexFormat.of().formatHex(ru.bitcoin.node.crypto.hash.Sha256.hash(new byte[]{0x51})));
        var pool = new Mempool();
        // Seed an already admitted pool; admission policy/cluster discovery is not
        // the operation measured here. Revalidation reconstructs the spent index.
        var field = Mempool.class.getDeclaredField("entries");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var entries = (Map<Hash256, MempoolEntry>) field.get(pool);
        for (int i = 0; i < 2000; i++) {
            var funding = new OutPoint(Hash256.fromDisplayHex(String.format("%064x", i + 1)), new UInt32(0));
            var tx = new Transaction(2,
                    List.of(new TxIn(funding, new byte[0], TxIn.FINAL_SEQUENCE,
                            new Witness(List.of(new byte[]{0x51})))),
                    List.of(new TxOut(90_000, script), new TxOut(0, new byte[]{0x6a})), new UInt32(0));
            entries.put(tx.txId(), new MempoolEntry(tx, 10_000, TransactionWeight.calculate(tx), 123, 0));
            pool.markUnbroadcast(tx.txId());
        }
        var before = pool.entries();
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = allocations.getThreadAllocatedBytes(thread);
        assertTrue(pool.revalidate(new MempoolValidationContext(200, 600_000_000, h -> 500_000_000),
                p -> Optional.of(new UtxoEntry(100_000, script, 100, false)), Set.of()).isEmpty());
        long allocated = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
        assertTrue(allocated < 128L * 1024 * 1024,
                "Revalidation must not allocate quadratic map copies: " + allocated);
        assertEquals(before, pool.entries());
        assertEquals(2000, pool.unbroadcastTransactions().size());
        for (var entry : before) {
            assertEquals(entry.transaction(), pool.spendingTransaction(
                    entry.transaction().inputs().getFirst().previousOutput()).orElseThrow());
        }
    }
}
