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

class MissingNetworkProbeTest {
    @Test void addsMissingIpv6AndRetainsItWhenReturningToEightFullRelayPeers() throws Exception { probe(8, false, false); }
    @Test void doesNotProbeWhenBothNetworksAlreadyHaveConnections() throws Exception { probe(8, true, false); }
    @Test void reducedOutboundTargetDisablesExtraNetworkProbes() throws Exception { probe(7, false, false); }
    @Test void failedPreferredNetworkAttemptWaitsForNextInterval() throws Exception { probe(8, false, true); }
    @Test void noProbeWhenMaximumConnectionsLeavesNoSpareOutboundCapacity() throws Exception { probe(8, false, false, 8); }
    @Test void fractionalProbeDeadlineIsStrictAndNotRoundedToSeconds() throws Exception { probe(8, false, false, Integer.MAX_VALUE, true); }

    private void probe(int target, boolean ipv6Initially, boolean failIpv6) throws Exception {
        probe(target, ipv6Initially, failIpv6, Integer.MAX_VALUE);
    }
    private void probe(int target, boolean ipv6Initially, boolean failIpv6, int maximum) throws Exception {
        probe(target, ipv6Initially, failIpv6, maximum, false);
    }
    private void probe(int target, boolean ipv6Initially, boolean failIpv6, int maximum, boolean fractional) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0); int port = server.localPort();
            var addresses = new PeerAddressManager(); var candidates = new ArrayList<PeerAddress>();
            for (var host : List.of("8.8.8.8", "9.9.9.9", "11.1.1.1", "12.1.1.1", "13.1.1.1", "14.1.1.1", "15.1.1.1", "2606:4700:4700::1111")) {
                var address = new PeerAddress(InetAddress.getByName(host), port, 9);
                candidates.add(address); addresses.add(address, Instant.now());
            }
            var attempts = new AtomicInteger(); var client = new BitcoinClient(parameters);
            PeerConnector connector = (host, p, height) -> {
                attempts.incrementAndGet();
                if (failIpv6 && host.contains(":")) throw new java.io.IOException("Controlled IPv6 failure");
                return client.connectManaged("127.0.0.1", p, height, PeerConnectionRole.FULL_RELAY);
            };
            var selector = new OutboundPeerSelector(addresses) {
                public Optional<PeerAddress> select(Set<PeerAddress> excluded) {
                    return candidates.stream().filter(address -> !excluded.contains(address)).findFirst();
                }
            };
            var manager = new OutboundPeerManager(connector, peers, addresses, selector, Instant::now);
            var initial = client.connectManaged("127.0.0.1", port, 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            var view = new OutboundPeerRecoveryView() {
                public long lastTipUpdateSeconds() { return Long.MAX_VALUE; }
                public boolean enabled() { return true; }
                public boolean blocksInFlight() { return false; }
                public boolean blocksInFlight(Peer peer) { return false; }
                public boolean hasTipWork(Peer peer) { return false; }
            };
            var intervals = new AtomicInteger();
            var clock = new java.util.concurrent.atomic.AtomicLong(PeerConnection.currentTimeMicros());
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, target, 0,
                    Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofHours(1), Duration.ZERO)
                    .recovery(view, 600).maximumAutomaticConnections(maximum)
                    .recoveryTimeMicros(clock::get)
                    .networkProbeDelayMicros(() -> intervals.getAndIncrement() == 0 ? (fractional ? 125_000 : 1_000_000) : 3_600_000_000L)) {
                supervisor.start(new OutboundPeerConnection(initial,
                        new PeerAddress(InetAddress.getByName(ipv6Initially ? "2606:4700:4700::1001" : "1.1.1.1"), port, 9)));
                await(() -> supervisor.activeConnectionCount() == target);
                if (fractional) {
                    long deadline = clock.get() + 125_000;
                    supervisor.checkRecoveryAtMicros(deadline - 1, false);
                    assertEquals(target - 1, attempts.get());
                    supervisor.checkRecoveryAtMicros(deadline, false);
                    assertEquals(target - 1, attempts.get());
                    supervisor.checkRecoveryAtMicros(deadline + 1, false);
                    await(() -> supervisor.activeConnectionCount() == target + 1);
                    assertEquals(target, attempts.get());
                    return;
                }
                long now = System.currentTimeMillis() / 1000;
                supervisor.checkRecovery(now + 2);
                if (target != 8 || ipv6Initially || maximum == target) {
                    assertEquals(target - 1, attempts.get());
                    assertEquals(target, supervisor.activeConnectionCount());
                    return;
                }
                await(() -> attempts.get() == target);
                if (failIpv6) {
                    supervisor.checkRecovery(now + 100);
                    assertEquals(target, attempts.get());
                    assertEquals(target, supervisor.activeConnectionCount());
                    return;
                }
                await(() -> supervisor.activeConnectionCount() == target + 1);
                var ipv6 = supervisor.connections().stream().filter(c -> c.address().network() == PeerAddressNetwork.IPV6).findFirst().orElseThrow();
                supervisor.checkRecovery(now + 34);
                await(() -> supervisor.activeConnectionCount() == target);
                assertTrue(ipv6.peer().isReady(), "The only connection to IPv6 must survive ordinary extra-peer eviction");
                assertEquals(7, supervisor.connections().stream().filter(c -> c.address().network() == PeerAddressNetwork.IPV4).count());
                assertEquals(target, attempts.get());
            }
        }
    }

    @Test void hardNetworkPreferenceNeverFallsBackToAnotherNetworkAndHonorsBans() throws Exception {
        try (var peers = new PeerManager()) {
            var addresses = new PeerAddressManager();
            var v4 = new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, 9);
            var v6 = new PeerAddress(InetAddress.getByName("2606:4700:4700::1111"), 8333, 9);
            addresses.add(v4, Instant.now()); addresses.add(v6, Instant.now());
            var manager = new OutboundPeerManager((host, port, height) -> { throw new AssertionError("Selection must not connect"); },
                    peers, addresses, new OutboundPeerSelector(addresses), Instant::now);
            assertEquals(Set.of(PeerAddressNetwork.IPV4, PeerAddressNetwork.IPV6), manager.knownAutomaticNetworks());
            assertEquals(v6, manager.selectPersistentCandidate(List.of(), Set.of(), PeerAddressNetwork.IPV6));
            peers.banManager().ban(v6.hostAddress(), 3600, false);
            assertThrows(java.io.IOException.class, () -> manager.selectPersistentCandidate(List.of(), Set.of(), PeerAddressNetwork.IPV6));
            assertEquals(v4, manager.selectPersistentCandidate(List.of(v6), Set.of()));
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
