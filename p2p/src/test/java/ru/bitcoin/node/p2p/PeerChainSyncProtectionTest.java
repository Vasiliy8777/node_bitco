package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

class PeerChainSyncProtectionTest {
    @Test void firstFourHeaderEventsWinRegardlessOfPoolOrderAndRemovalFreesQuota() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var manager = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            var peers = new ArrayList<Peer>();
            for (int i = 0; i < 5; i++) {
                var peer = client.connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
                peers.add(peer); manager.add(peer, PeerConnectionRole.FULL_RELAY);
            }
            for (int i = 4; i >= 1; i--) assertTrue(manager.protectChainSync(peers.get(i)));
            assertFalse(manager.protectChainSync(peers.getFirst()));
            assertTrue(manager.protectChainSync(peers.get(4)), "Repeated header events must not consume another slot");
            peers.get(3).close();
            assertFalse(manager.hasChainSyncProtection(peers.get(3)));
            assertTrue(manager.protectChainSync(peers.getFirst()));
            manager.remove(peers.get(4));
            assertFalse(manager.hasChainSyncProtection(peers.get(4)));
            peers.get(4).close();
        }
    }

    @Test void onlyReadyManagedFullRelayConnectionsCanBeProtectedAndCloseCleansState() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var manager = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
        server.start(0, 0);
        var client = new BitcoinClient(parameters);
        for (var role : List.of(PeerConnectionRole.INBOUND, PeerConnectionRole.BLOCK_RELAY_ONLY, PeerConnectionRole.FEELER)) {
            var peer = client.connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY); manager.add(peer, role);
            assertFalse(manager.protectChainSync(peer));
        }
        var peer = new Peer(new PeerConnection(parameters), 9, 0, true); manager.add(peer, PeerConnectionRole.FULL_RELAY);
        assertFalse(manager.protectChainSync(peer));
        var ready = client.connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
        manager.add(ready, PeerConnectionRole.FULL_RELAY); assertTrue(manager.protectChainSync(ready));
        manager.close();
        assertFalse(manager.hasChainSyncProtection(ready));
        assertFalse(manager.protectChainSync(ready));
        }
    }
}
