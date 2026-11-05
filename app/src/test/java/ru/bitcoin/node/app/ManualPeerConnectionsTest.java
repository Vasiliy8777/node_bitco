package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.service.ManualPeerConnections;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualPeerConnectionsTest {
    @Test void explicitEndpointsAreBoundedAndPrivacyNamesAreNotResolvedDirectly() {
        var endpoints = ManualPeerConnections.parse("EXAMPLE.com:8333,[::1]:18444,example.com:8333,127.0.0.1", 8333);
        assertEquals(List.of(new ManualPeerConnections.Endpoint("example.com", 8333),
                new ManualPeerConnections.Endpoint("::1", 18444), new ManualPeerConnections.Endpoint("127.0.0.1", 8333)), endpoints);
        for (String invalid : new String[]{"", ":0", "x.onion:8333", "x.i2p:8333", "[::1", "host:65536"}) {
            if (invalid.isEmpty()) assertTrue(ManualPeerConnections.parse(invalid, 8333).isEmpty());
            else assertThrows(IllegalArgumentException.class, () -> ManualPeerConnections.parse(invalid, 8333));
        }
        assertThrows(IllegalArgumentException.class, () -> ManualPeerConnections.parse(
                "a,b,c,d,e,f,g,h,i", 8333));
    }
    @Test void configuredManualPeerReconnectsAndShutsDownIndependently() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var clients = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0);
            try (var manual = new ManualPeerConnections(new BitcoinClient(parameters).independentClient(), clients, () -> 0,
                    List.of(new ManualPeerConnections.Endpoint("127.0.0.1", server.localPort())))) {
                manual.start(); var first = awaitPeer(clients, null);
                assertTrue(first.isManualConnection()); assertEquals(PeerConnectionRole.MANUAL, clients.roleOf(first));
                assertFalse(clients.protectChainSync(first));
                first.close(); var second = awaitPeer(clients, first);
                assertNotSame(first, second); assertTrue(second.isManualConnection());
                manual.close(); assertEquals(PeerState.CLOSED, second.state()); assertFalse(manual.isRunning());
                assertThrows(IllegalStateException.class, manual::start);
            }
        }
    }
    private static Peer awaitPeer(PeerManager peers, Peer previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var found = peers.readyPeers().stream().filter(peer -> peer != previous).findFirst();
            if (found.isPresent()) return found.get(); Thread.sleep(10);
        }
        throw new AssertionError("Manual connection did not become ready");
    }
    @Test void stopDuringRegistrationCannotPublishALatePeer() throws Exception {
        var connector = mock(PeerConnector.class); var peer = mock(Peer.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopping = new java.util.concurrent.CountDownLatch(1);
        when(peer.isReady()).thenReturn(true);
        when(connector.connectManaged(anyString(), anyInt(), anyInt(), eq(PeerConnectionRole.MANUAL))).thenReturn(peer);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; }).when(peer).addCloseListener(any());
        doAnswer(call -> { stopping.countDown(); return null; }).when(connector).cancelPendingConnections();
        try (var peers = new PeerManager(); var manual = new ManualPeerConnections(connector, peers, () -> 0,
                List.of(new ManualPeerConnections.Endpoint("127.0.0.1", 8333)))) {
            manual.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var closed = java.util.concurrent.CompletableFuture.runAsync(manual::close);
            assertTrue(stopping.await(3, TimeUnit.SECONDS)); release.countDown(); closed.get(3, TimeUnit.SECONDS);
            assertTrue(peers.isEmpty()); verify(peer, atLeastOnce()).close();
        } finally { release.countDown(); }
    }
}
