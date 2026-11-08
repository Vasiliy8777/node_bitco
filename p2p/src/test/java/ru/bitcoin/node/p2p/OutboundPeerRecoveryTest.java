package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.net.InetAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OutboundPeerRecoveryTest {
    @Test void evictionUsesTcpCreationOrderWhenHandshakesCompleteInReverseOrder() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0); int port = server.localPort();
            var older = new Peer(new PeerConnection(parameters), ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES, 0, true);
            var newer = new Peer(new PeerConnection(parameters), ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES, 0, true);
            try (older; newer) {
                older.connect("127.0.0.1", port);
                newer.connect("127.0.0.1", port);
                newer.handshake(false);
                older.handshake(false);
                assertTrue(older.connectionId() < newer.connectionId());
                assertTrue(older.connectionEstablishedAtSeconds() <= newer.connectionEstablishedAtSeconds());
                peers.add(newer, PeerConnectionRole.FULL_RELAY);
                var addresses = new PeerAddressManager();
                addresses.add(new PeerAddress(InetAddress.getByName("8.8.8.8"), port, 9), Instant.now());
                var manager = new OutboundPeerManager((host, p, height) -> older, peers, addresses,
                        new OutboundPeerSelector(addresses), Instant::now);
                var view = new OutboundPeerRecoveryView() {
                    public long lastTipUpdateSeconds() { return 1; }
                    public boolean enabled() { return true; }
                    public boolean blocksInFlight() { return false; }
                    public boolean blocksInFlight(Peer peer) { return false; }
                    public boolean hasTipWork(Peer peer) { return false; }
                };
                try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 1, 0).recovery(view, 600)) {
                    supervisor.start(new OutboundPeerConnection(newer,
                            new PeerAddress(InetAddress.getByName("1.1.1.1"), port, 9)));
                    long now = System.currentTimeMillis() / 1000;
                    supervisor.checkRecovery(now);
                    await(() -> supervisor.activeConnectionCount() == 2);
                    supervisor.checkRecovery(now + 31);
                    await(() -> supervisor.activeConnectionCount() == 1);
                    assertSame(older, supervisor.connection().peer(), "READY installation order replaced TCP creation order");
                    assertFalse(newer.isReady());
                }
            }
        }
    }

    @Test void headerChecksRunWithoutWaitingForTheFortyFiveSecondEvictionTimer() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            var addresses = new PeerAddressManager();
            var manager = new OutboundPeerManager((host, port, height) -> { throw new AssertionError("No extra connection expected"); },
                    peers, addresses, new OutboundPeerSelector(addresses), Instant::now);
            var initial = client.connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            var checked = new java.util.concurrent.CountDownLatch(1);
            var view = new OutboundPeerRecoveryView() {
                public long lastTipUpdateSeconds() { return Long.MAX_VALUE; }
                public boolean enabled() { return false; }
                public boolean blocksInFlight() { return false; }
                public boolean blocksInFlight(Peer peer) { return false; }
                public boolean hasTipWork(Peer peer) { return false; }
                public ChainWork chainWork(Peer peer) {
                    return new ChainWork(java.math.BigInteger.ONE, java.math.BigInteger.ONE, List.of());
                }
                public void requestHeaders(Peer peer, List<ru.bitcoin.node.common.types.Hash256> locator) { checked.countDown(); }
            };
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 1, 0).recovery(view, 600)) {
                supervisor.start(new OutboundPeerConnection(initial,
                        new PeerAddress(InetAddress.getByName("1.1.1.1"), server.localPort(), 9)));
                assertTrue(checked.await(3, TimeUnit.SECONDS), "Header checks still waited for the 45-second eviction timer");
                assertTrue(initial.isReady());
            }
        }
    }

    @Test void laggingPeerGetsFinalHeadersRequestBeforeDisconnectAndReplacement() throws Exception { chainCatchup(false); }
    @Test void chainworkProtectionSurvivesSubsequentTipAdvancement() throws Exception { chainCatchup(true); }

    private void chainCatchup(boolean protect) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0); int port = server.localPort();
            var client = new BitcoinClient(parameters);
            var addresses = new PeerAddressManager();
            addresses.add(new PeerAddress(InetAddress.getByName("8.8.8.8"), port, 9), Instant.now());
            var manager = new OutboundPeerManager((host, p, height) -> client.connectManaged("127.0.0.1", p, height,
                    PeerConnectionRole.FULL_RELAY), peers, addresses, new OutboundPeerSelector(addresses), Instant::now);
            var initial = client.connectManaged("127.0.0.1", port, 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            var tip = new java.util.concurrent.atomic.AtomicLong(100);
            var requests = new AtomicInteger();
            var initialRequests = new AtomicInteger();
            var syncStarted = new java.util.concurrent.atomic.AtomicBoolean();
            var view = new OutboundPeerRecoveryView() {
                public long lastTipUpdateSeconds() { return Long.MAX_VALUE; }
                public boolean enabled() { return true; }
                public boolean blocksInFlight() { return false; }
                public boolean blocksInFlight(Peer peer) { return false; }
                public boolean hasTipWork(Peer peer) { return protect && tip.get() == 100; }
                public boolean headersSyncStarted(Peer peer) { return syncStarted.get(); }
                public ChainWork chainWork(Peer peer) {
                    return new ChainWork(java.math.BigInteger.valueOf(tip.get()),
                            java.math.BigInteger.valueOf(protect ? 100 : 1), List.of());
                }
                public void requestHeaders(Peer peer, List<ru.bitcoin.node.common.types.Hash256> locator) { requests.incrementAndGet(); }
                public void requestInitialHeaders(Peer peer, List<ru.bitcoin.node.common.types.Hash256> locator) {
                    initialRequests.incrementAndGet();
                    requestHeaders(peer, locator);
                }
            };
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 1, 0,
                    Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofHours(1), Duration.ZERO).recovery(view, 600)) {
                supervisor.start(new OutboundPeerConnection(initial,
                        new PeerAddress(InetAddress.getByName("1.1.1.1"), port, 9)));
                long now = System.currentTimeMillis() / 1000;
                supervisor.checkRecovery(now);
                assertEquals(1, requests.get());
                assertEquals(1, initialRequests.get(), "Seed initialization must use the initial-admission callback");
                supervisor.checkRecovery(now + 3600);
                assertEquals(1, requests.get(), "Deferred initial admission must not start chainwork probes");
                assertTrue(initial.isReady(), "A peer waiting for initial admission must not be evicted");
                syncStarted.set(true);
                now += 3600;
                supervisor.checkRecovery(now);
                tip.set(200);
                supervisor.checkRecovery(now + 1200);
                assertEquals(1, requests.get());
                supervisor.checkRecovery(now + 1201);
                assertEquals(protect ? 1 : 2, requests.get());
                assertEquals(1, initialRequests.get(), "Chainwork probing must not restart initial admission");
                assertTrue(initial.isReady());
                supervisor.checkRecovery(now + 1321);
                assertTrue(initial.isReady());
                supervisor.checkRecovery(now + 1322);
                if (protect) assertTrue(initial.isReady());
                else {
                    await(() -> supervisor.activeConnectionCount() == 1 && supervisor.connection().peer() != initial && requests.get() >= 3);
                    assertFalse(initial.isReady());
                    assertTrue(requests.get() >= 3, "Replacement immediately requests headers");
                    assertTrue(initialRequests.get() >= 2, "Replacement must also use initial admission");
                }
            }
        }
    }

    @Test void probesOnceEvictsExtraAndKeepsOrdinaryPoolSupervised() throws Exception { recover(false); }
    @Test void usefulNewPeerReplacesOldPeerAndRetainsItsCloseSupervision() throws Exception { recover(true); }

    private void recover(boolean useful) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager();
             var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0);
            int port = server.localPort();
            var addresses = new PeerAddressManager();
            var candidate = new PeerAddress(InetAddress.getByName("8.8.8.8"), port, 9);
            addresses.add(candidate, Instant.now());
            var attempts = new AtomicInteger();
            var client = new BitcoinClient(parameters);
            PeerConnector connector = new PeerConnector() {
                public Peer connect(String host, int p, int height) throws java.io.IOException {
                    attempts.incrementAndGet();
                    return client.connectManaged("127.0.0.1", p, height, PeerConnectionRole.FULL_RELAY);
                }
            };
            var manager = new OutboundPeerManager(connector, peers, addresses,
                    new OutboundPeerSelector(addresses), Instant::now);
            var initial = client.connectManaged("127.0.0.1", port, 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            var downloading = new java.util.concurrent.atomic.AtomicBoolean();
            var view = new OutboundPeerRecoveryView() {
                public long lastTipUpdateSeconds() { return 1; }
                public boolean enabled() { return true; }
                public boolean blocksInFlight() { return downloading.get(); }
                public boolean blocksInFlight(Peer peer) { return downloading.get(); }
                public boolean hasTipWork(Peer peer) { return false; }
            };
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 1, 0,
                    Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofHours(1), Duration.ZERO).recovery(view, 600)) {
                supervisor.start(new OutboundPeerConnection(initial,
                        new PeerAddress(InetAddress.getByName("1.1.1.1"), port, 9)));
                long now = System.currentTimeMillis() / 1000;
                supervisor.checkRecovery(now);
                await(() -> supervisor.activeConnectionCount() == 2);
                var extra = supervisor.connections().stream().filter(c -> c.peer() != initial).findFirst().orElseThrow();
                if (useful) extra.peer().recordNewBlockAnnouncement();
                downloading.set(true);
                supervisor.checkRecovery(now + 31);
                assertEquals(2, supervisor.activeConnectionCount(), "Do not evict while a block is downloading");
                downloading.set(false);
                supervisor.checkRecovery(now + 32, false);
                assertEquals(2, supervisor.activeConnectionCount(), "Fast scheduling ticks must not run 45-second extra-peer eviction");
                supervisor.checkRecovery(now + 32);
                await(() -> supervisor.activeConnectionCount() == 1);
                assertSame(useful ? extra.peer() : initial, supervisor.connection().peer());
                supervisor.checkRecovery(now + 77);
                assertEquals(1, attempts.get(), "A successful eviction suppresses probes until the next stale check");
                if (useful) {
                    extra.peer().close();
                    await(() -> attempts.get() == 2 && supervisor.activeConnectionCount() == 1);
                    assertNotSame(extra.peer(), supervisor.connection().peer(), "Promoted connection keeps normal close/reconnect supervision");
                }
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
