package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.p2p.address.*;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BlockRelayAnchorIntegrationTest {
    @TempDir Path directory;
    @Test void reconnectsAnchorBeforeAddrmanAndPersistsOnlyBlockRelayPeers() throws Exception { connect(false); }
    @Test void failedAnchorIsTriedOnceThenFallsBackToAddrman() throws Exception { connect(true); }
    @Test void fullRelayWorkersCannotTakeTheReservedAnchorEndpoint() throws Exception { connect(false, 2); }

    private void connect(boolean failAnchor) throws Exception { connect(failAnchor, 1); }
    private void connect(boolean failAnchor, int fullRelayTargets) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var servingPeers = new PeerManager(); var peers = new PeerManager();
             var server = new BitcoinServer(parameters, servingPeers)) {
            server.start(0, 0);
            int port = server.localPort();
            var initialAddress = new PeerAddress(InetAddress.getByName("1.1.1.1"), port, 9);
            var anchor = new PeerAddress(InetAddress.getByName("8.8.8.8"), port, 9);
            var fallback = new PeerAddress(InetAddress.getByName("9.9.9.9"), port, 9);
            var store = new BlockRelayAnchorStore(directory, parameters.magic()); store.save(List.of(anchor));
            var attempted = new CopyOnWriteArrayList<String>();
            var client = new BitcoinClient(parameters);
            PeerConnector connector = new PeerConnector() {
                @Override public Peer connect(String host, int peerPort, int height) throws java.io.IOException {
                    return connectManaged(host, peerPort, height, PeerConnectionRole.FULL_RELAY);
                }
                @Override public Peer connectManaged(String host, int peerPort, int height, PeerConnectionRole role) throws java.io.IOException {
                    attempted.add(host);
                    if (host.equals("8.8.8.8") && failAnchor) throw new java.io.IOException("Controlled anchor failure");
                    // Logical public endpoints all use an isolated loopback test server.
                    return client.connectManaged("127.0.0.1", peerPort, height, role);
                }
            };
            var addresses = new PeerAddressManager(); addresses.add(fallback, Instant.now());
            var selector = new OutboundPeerSelector(addresses) {
                @Override public Optional<PeerAddress> select(Set<PeerAddress> excluded) {
                    return excluded.contains(fallback) ? Optional.empty() : Optional.of(fallback);
                }
            };
            var manager = new OutboundPeerManager(connector, peers, addresses, selector, Instant::now);
            var initial = client.connectManaged("127.0.0.1", port, 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, fullRelayTargets, 1,
                    Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofHours(1), Duration.ZERO).anchorStore(store)) {
                supervisor.start(new OutboundPeerConnection(initial, initialAddress));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (supervisor.activeConnectionCount() != fullRelayTargets + 1 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(fullRelayTargets + 1, supervisor.activeConnectionCount());
                var block = supervisor.connections().stream().filter(c -> c.role() == PeerConnectionRole.BLOCK_RELAY_ONLY).findFirst().orElseThrow();
                assertEquals(failAnchor ? fallback : anchor, block.address());
                assertNotNull(block.peer().remoteVersion());
                assertEquals(1, attempted.stream().filter("8.8.8.8"::equals).count());
                if (fullRelayTargets == 1)
                    assertEquals(failAnchor ? List.of("8.8.8.8", "9.9.9.9") : List.of("8.8.8.8"), attempted);
                else {
                    assertEquals(2, attempted.size());
                    assertEquals(Set.of("8.8.8.8", "9.9.9.9"), Set.copyOf(attempted));
                }
            }
            var saved = store.consume();
            assertEquals(1, saved.size());
            assertEquals(failAnchor ? fallback : anchor, saved.getFirst());
            assertEquals(VersionMessage.DEFAULT_SERVICES, saved.getFirst().services(), "Persist negotiated service flags");
        }
    }

    @Test void neverStartedOrDisabledSupervisorPreservesExistingAnchors() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var store = new BlockRelayAnchorStore(directory, parameters.magic());
        var anchor = new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, 9);
        store.save(List.of(anchor));
        try (var peers = new PeerManager()) {
            var addresses = new PeerAddressManager();
            var manager = new OutboundPeerManager((host, port, height) -> { throw new AssertionError("Offline supervisor must not connect"); },
                    peers, addresses, new OutboundPeerSelector(addresses), Instant::now);
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 0, 0).anchorStore(store)) { }
            assertEquals(List.of(anchor), store.consume());
        }
    }

    @Test void anchorEligibilityRespectsServicesIbdBansAndUnsupportedAddresses() throws Exception {
        var ibd = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var peers = new PeerManager()) {
            var addresses = new PeerAddressManager();
            var manager = new OutboundPeerManager((host, port, height) -> { throw new AssertionError("Eligibility must not connect"); },
                    peers, addresses, new OutboundPeerSelector(addresses), Instant::now, peers.discouragementManager(), ibd::get);
            assertFalse(manager.prepareAnchor(new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, VersionMessage.NODE_NETWORK)));
            var limited = new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS);
            assertFalse(manager.prepareAnchor(limited));
            ibd.set(false); assertTrue(manager.prepareAnchor(limited));
            for (var host : List.of("127.0.0.1", "0.1.2.3", "::1", "::", "2001:db8::1", "3fff::1", "255.255.255.255"))
                assertFalse(manager.prepareAnchor(new PeerAddress(InetAddress.getByName(host), 8333, 9)), host);
            peers.banManager().ban("8.8.8.8", 3600, false);
            assertFalse(manager.prepareAnchor(limited));
            peers.discouragementManager().discourage(InetAddress.getByName("9.9.9.9"));
            assertFalse(manager.prepareAnchor(new PeerAddress(InetAddress.getByName("9.9.9.9"), 8333, 9)));
        }
    }
}
