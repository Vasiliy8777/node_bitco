package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerAccessConnectionTest {
    @Test void remoteVersionServicesCannotGrantLocalPermissions() throws Exception {
        try (var serving = new PeerManager(); var clients = new PeerManager();
             var server = new BitcoinServer(NetworkParametersRegistry.regtest(), serving)) {
            server.start(0, 0);
            var client = new BitcoinClient(NetworkParametersRegistry.regtest(), Long.MAX_VALUE, true)
                    .connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
            clients.add(client);
            var inbound = inbound(serving);
            assertEquals(Long.MAX_VALUE, inbound.remoteVersion().services());
            assertTrue(inbound.permissions().isEmpty()); assertFalse(inbound.isManualConnection());
            inbound.disconnectForProtocolViolation("Invalid announcement", null);
            assertEquals(PeerState.CLOSED, inbound.state());
            assertFalse(serving.discouragementManager().isDiscouraged(java.net.InetAddress.getLoopbackAddress()));
        }
    }
    @Test void configuredNobanSurvivesMisbehaviorButNotExplicitClose() throws Exception {
        try (var serving = new PeerManager(); var clients = new PeerManager();
             var server = new BitcoinServer(NetworkParametersRegistry.regtest(), serving)) {
            server.permissionPolicy(PeerPermissionPolicy.parse("noban@127.0.0.1/32")); server.start(0, 0);
            serving.banManager().ban("127.0.0.1", 3600, false);
            var client = new BitcoinClient(NetworkParametersRegistry.regtest()).connectManaged("127.0.0.1", server.localPort(), 0);
            clients.add(client);
            var inbound = inbound(serving);
            assertTrue(inbound.hasPermission(PeerPermission.NOBAN)); assertTrue(inbound.hasPermission(PeerPermission.DOWNLOAD));
            assertThrows(IllegalStateException.class, () -> server.permissionPolicy(PeerPermissionPolicy.none()));
            inbound.disconnectForProtocolViolation("Invalid announcement", null);
            assertTrue(inbound.isReady());
            inbound.close(); assertEquals(PeerState.CLOSED, inbound.state());
        }
    }
    @Test void manualClientHasNoAutomaticSlotOrNobanPermission() throws Exception {
        try (var serving = new PeerManager(); var clients = new PeerManager();
             var server = new BitcoinServer(NetworkParametersRegistry.regtest(), serving)) {
            server.start(0, 0);
            var manual = new BitcoinClient(NetworkParametersRegistry.regtest()).connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.MANUAL);
            clients.add(manual, PeerConnectionRole.MANUAL);
            assertTrue(manual.isManualConnection()); assertTrue(manual.permissions().isEmpty());
            assertFalse(clients.protectChainSync(manual));
            assertTrue(clients.hasRole(manual, PeerConnectionRole.MANUAL));
            manual.disconnectForProtocolViolation("Invalid announcement", null);
            assertTrue(manual.isReady()); manual.close(); assertEquals(PeerState.CLOSED, manual.state());
        }
    }
    @Test void protectedInboundPeerCannotBeEvictedButCapacityStillApplies() throws Exception {
        try (var serving = new PeerManager(); var clients = new PeerManager();
             var server = new BitcoinServer(NetworkParametersRegistry.regtest(), serving,
                     ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES, true, 1)) {
            server.permissionPolicy(PeerPermissionPolicy.parse("noban@127.0.0.1/32")); server.start(0, 0);
            var first = new BitcoinClient(NetworkParametersRegistry.regtest()).connectManaged("127.0.0.1", server.localPort(), 0);
            clients.add(first); var protectedInbound = inbound(serving);
            assertThrows(java.io.IOException.class, () -> new BitcoinClient(NetworkParametersRegistry.regtest())
                    .connectManaged("127.0.0.1", server.localPort(), 0));
            assertTrue(protectedInbound.isReady()); assertTrue(first.isReady()); assertEquals(1, serving.size());
        }
    }
    private static Peer inbound(PeerManager peers) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (peers.readyPeers().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(peers.readyPeers().isEmpty()); return peers.readyPeers().getFirst();
    }
}
