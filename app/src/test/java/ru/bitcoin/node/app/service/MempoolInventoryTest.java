package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.protocol.transaction.Transaction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MempoolInventoryTest {
    @Test void core32FeeFilterRoundsUpAndIgnoresMiningDelta() {
        var tx = mock(Transaction.class);
        assertFalse(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, 100, 400, 0, 0, 10000), 1001));
        assertTrue(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, 101, 400, 0, 0, -100), 1001));
        assertTrue(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, 100, 400, 0), 1000));
    }
    @Test void positiveFilterRequiresOneSatoshiAndDoesNotOverflow() {
        var tx = mock(Transaction.class);
        assertFalse(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, 0, 400, 0), 1));
        assertTrue(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, 0, 400, 0), 0));
        assertFalse(MempoolInventory.passesFeeFilter(new MempoolEntry(tx, Long.MAX_VALUE, 4000000, 0), Long.MAX_VALUE));
    }
}
