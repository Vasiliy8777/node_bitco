package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import static org.junit.jupiter.api.Assertions.*;

final class TxRelayStateTest {

    @Test
    void startsWithNoFeeFilter() {
        assertEquals(0L, new TxRelayState().feeFilterSatPerKvB());
    }

    @Test
    void storesPeerFeeFilter() {
        TxRelayState state = new TxRelayState();
        state.feeFilterSatPerKvB(12_345L);
        assertEquals(12_345L, state.feeFilterSatPerKvB());
    }

    @Test
    void rejectsNegativePolicyValue() {
        TxRelayState state = new TxRelayState();
        assertThrows(IllegalArgumentException.class, () -> state.feeFilterSatPerKvB(-1L));
    }

    @Test
    void remembersKnownTransactionIds() {
        TxRelayState state = new TxRelayState();
        Hash256 hash = hash(7);

        assertFalse(state.knows(hash));
        state.markKnown(hash);
        assertTrue(state.knows(hash));
    }

    @Test
    void knownSetIsBounded() {
        TxRelayState state = new TxRelayState();

        for (int i = 0; i < TxRelayState.MAX_KNOWN_TRANSACTION_IDS + 10; i++) {
            byte[] bytes = new byte[32];
            bytes[0] = (byte) i;
            bytes[1] = (byte) (i >>> 8);
            bytes[2] = (byte) (i >>> 16);
            bytes[3] = (byte) (i >>> 24);
            state.markKnown(new Hash256(bytes));
        }

        assertEquals(TxRelayState.MAX_KNOWN_TRANSACTION_IDS, state.knownCount());
    }

    private static Hash256 hash(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return new Hash256(bytes);
    }

    @Test
    void tracksOutboundFeeFilterIndependentlyFromReceivedFilter() {
        TxRelayState state = new TxRelayState();

        state.feeFilterSatPerKvB(2_000);
        state.feeFilterSentSatPerKvB(3_000);
        state.nextFeeFilterSendNanos(123_456L);

        assertEquals(2_000L, state.feeFilterSatPerKvB());
        assertEquals(3_000L, state.feeFilterSentSatPerKvB());
        assertEquals(123_456L, state.nextFeeFilterSendNanos());
    }

}
