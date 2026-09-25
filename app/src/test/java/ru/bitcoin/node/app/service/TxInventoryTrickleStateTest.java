package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TxInventoryTrickleStateTest {

    @Test
    void queuesOnceAndDrainsInInsertionOrder() {
        TxRelayState state = new TxRelayState();

        Hash256 tx1 = hash(1);
        Hash256 wtx1 = hash(2);
        Hash256 tx2 = hash(3);
        Hash256 wtx2 = hash(4);

        state.queue(tx1, wtx1, 2_000);
        state.queue(tx1, wtx1, 2_000);
        state.queue(tx2, wtx2, 3_000);

        assertEquals(2, state.pendingCount());

        List<TxRelayState.PendingAnnouncement> drained =
                state.takeForRelay(true, 1);

        assertEquals(1, drained.size());
        assertEquals(wtx1, drained.getFirst().wtxId());
        assertEquals(1, state.pendingCount());

        drained = state.takeForRelay(true, 10);
        assertEquals(List.of(wtx2),
                drained.stream().map(TxRelayState.PendingAnnouncement::wtxId).toList());
        assertEquals(0, state.pendingCount());
    }

    @Test
    void rechecksFeeFilterAtSendTime() {
        TxRelayState state = new TxRelayState();
        state.queue(hash(1), hash(2), 1_000);
        state.feeFilterSatPerKvB(2_000);

        assertTrue(state.takeForRelay(true, 70).isEmpty());
        assertEquals(0, state.pendingCount());
    }

    @Test
    void rechecksKnownInventoryAtSendTimeForNegotiatedId() {
        TxRelayState state = new TxRelayState();
        Hash256 txid = hash(1);
        Hash256 wtxid = hash(2);

        state.queue(txid, wtxid, 5_000);
        state.markKnown(wtxid);

        assertTrue(state.takeForRelay(true, 70).isEmpty());
        assertEquals(0, state.pendingCount());
    }

    @Test
    void legacyPeerChecksTxidRatherThanWtxid() {
        TxRelayState state = new TxRelayState();
        Hash256 txid = hash(1);
        Hash256 wtxid = hash(2);

        state.queue(txid, wtxid, 5_000);
        state.markKnown(wtxid);

        assertEquals(
                List.of(txid),
                state.takeForRelay(false, 70).stream()
                        .map(TxRelayState.PendingAnnouncement::txId)
                        .toList()
        );
    }

    @Test
    void nextSendTimeIsStoredPerPeerState() {
        TxRelayState state = new TxRelayState();
        assertEquals(0L, state.nextInventorySendNanos());
        state.nextInventorySendNanos(123_456L);
        assertEquals(123_456L, state.nextInventorySendNanos());
    }

    private static Hash256 hash(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return new Hash256(bytes);
    }
}
