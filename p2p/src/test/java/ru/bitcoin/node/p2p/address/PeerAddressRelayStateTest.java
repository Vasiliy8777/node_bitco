package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class PeerAddressRelayStateTest {
    @Test void suppressesKnownAndQueuedDuplicates() throws Exception {
        PeerAddressRelayState state = new PeerAddressRelayState();
        PeerAddress address = new PeerAddress(InetAddress.getByName("203.0.113.8"), 8333, 1L);
        assertTrue(state.enable());
        state.queue(address);
        state.queue(address);
        assertEquals(1, state.queuedCount());
        var drained = state.drainCompatible(false);
        assertEquals(1, drained.size());
        assertTrue(state.knows(address));
        state.queue(address);
        assertEquals(0, state.queuedCount());
    }

    @Test void legacyPeerDropsNonLegacyAddress() {
        PeerAddressRelayState state = new PeerAddressRelayState();
        state.enable();
        PeerAddress tor = new PeerAddress(PeerAddressNetwork.TORV3, new byte[32], 8333, 1L);
        state.queue(tor);
        assertTrue(state.drainCompatible(false).isEmpty());
    }

    @Test void knownSetIsBounded() throws Exception {
        PeerAddressRelayState state = new PeerAddressRelayState();
        state.enable();
        for (int i = 0; i < PeerAddressRelayState.MAX_KNOWN + 25; i++) {
            byte[] ip = {(byte)198, 51, (byte)(i >>> 8), (byte)i};
            state.markKnown(new PeerAddress(InetAddress.getByAddress(ip), 8333, 1L));
        }
        assertEquals(PeerAddressRelayState.MAX_KNOWN, state.knownCount());
    }

    @Test void queueIsBounded() throws Exception {
        PeerAddressRelayState state = new PeerAddressRelayState();
        state.enable();
        for (int i = 0; i < PeerAddressRelayState.MAX_QUEUED + 25; i++) {
            byte[] ip = {(byte)203, 0, (byte)(i >>> 8), (byte)i};
            state.queue(new PeerAddress(InetAddress.getByAddress(ip), 8333, 1L));
        }
        assertEquals(PeerAddressRelayState.MAX_QUEUED, state.queuedCount());
    }
}
