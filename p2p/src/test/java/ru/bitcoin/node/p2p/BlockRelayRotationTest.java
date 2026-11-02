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

class BlockRelayRotationTest {
    @Test void dropsUnproductiveExtraBlockPeer() throws Exception { rotate(false, false); }
    @Test void promotesUsefulBlockPeerAndKeepsItsReconnectSupervision() throws Exception { rotate(true, false); }
    @Test void failedBlockProbeDoesNotRetryUntilNextRandomInterval() throws Exception { rotate(false, true); }
    @Test void pendingBlockHandshakeAndStaleFullProbeShareOneExtraAllowance() throws Exception { rotate(false, false, true); }
    @Test void fractionalBlockProbeDeadlineIsStrictAndNotRoundedToSeconds() throws Exception { rotate(false, false, false, true); }

    private void rotate(boolean useful, boolean failProbe) throws Exception {
        rotate(useful, failProbe, false);
    }
    private void rotate(boolean useful, boolean failProbe, boolean sharedBudget) throws Exception {
        rotate(useful, failProbe, sharedBudget, false);
    }
    private void rotate(boolean useful, boolean failProbe, boolean sharedBudget, boolean fractional) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var serving = new PeerManager(); var peers = new PeerManager(); var server = new BitcoinServer(parameters, serving)) {
            server.start(0, 0); int port = server.localPort();
            var addresses = new PeerAddressManager();
            var candidates = new ArrayList<PeerAddress>();
            for (var host : List.of("8.8.8.8", "9.9.9.9", "11.1.1.1")) {
                var address = new PeerAddress(InetAddress.getByName(host), port, 9);
                candidates.add(address); addresses.add(address, Instant.now());
            }
            var attempts = new AtomicInteger(); var initialRequests = new AtomicInteger();
            var stale = new java.util.concurrent.atomic.AtomicBoolean();
            var probeEntered = new java.util.concurrent.CountDownLatch(1);
            var releaseProbe = new java.util.concurrent.CountDownLatch(1);
            var fullStarted = new java.util.concurrent.CountDownLatch(1);
            var client = new BitcoinClient(parameters);
            PeerConnector connector = new PeerConnector() {
                public Peer connect(String host, int p, int height) throws java.io.IOException {
                    return connectManaged(host, p, height, PeerConnectionRole.FULL_RELAY);
                }
                public Peer connectManaged(String host, int p, int height, PeerConnectionRole role) throws java.io.IOException {
                    int attempt = attempts.incrementAndGet();
                    if (role == PeerConnectionRole.FULL_RELAY && sharedBudget) fullStarted.countDown();
                    else assertEquals(PeerConnectionRole.BLOCK_RELAY_ONLY, role);
                    if (sharedBudget && attempt == 3) {
                        probeEntered.countDown();
                        try { if (!releaseProbe.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Probe release timed out"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                    }
                    if (failProbe && attempt == 3) throw new java.io.IOException("Controlled block probe failure");
                    return client.connectManaged("127.0.0.1", p, height, role);
                }
            };
            var selector = new OutboundPeerSelector(addresses) {
                public Optional<PeerAddress> select(Set<PeerAddress> excluded) {
                    return candidates.stream().filter(address -> !excluded.contains(address)).findFirst();
                }
            };
            var manager = new OutboundPeerManager(connector, peers, addresses, selector, Instant::now);
            var initial = client.connectManaged("127.0.0.1", port, 0, PeerConnectionRole.FULL_RELAY);
            peers.add(initial, PeerConnectionRole.FULL_RELAY);
            var downloading = new java.util.concurrent.atomic.AtomicBoolean();
            var view = new OutboundPeerRecoveryView() {
                public long lastTipUpdateSeconds() { return stale.get() ? 1 : Long.MAX_VALUE; }
                public boolean enabled() { return true; }
                public boolean directFetch() { return true; }
                public boolean blocksInFlight() { return downloading.get(); }
                public boolean blocksInFlight(Peer peer) { return downloading.get(); }
                public boolean hasTipWork(Peer peer) { return peer == initial; }
                public ChainWork chainWork(Peer peer) {
                    return new ChainWork(java.math.BigInteger.ONE, java.math.BigInteger.ONE, List.of());
                }
                public void requestHeaders(Peer peer, List<ru.bitcoin.node.common.types.Hash256> locator) { initialRequests.incrementAndGet(); }
            };
            var intervals = new AtomicInteger();
            var clock = new java.util.concurrent.atomic.AtomicLong(PeerConnection.currentTimeMicros());
            try (var supervisor = new OutboundPeerSupervisor(manager, () -> 0, 1, 2,
                    Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofHours(1), Duration.ZERO)
                    .recovery(view, 600).recoveryTimeMicros(clock::get)
                    .blockProbeDelayMicros(() -> intervals.getAndIncrement() == 0 ? (fractional ? 125_000 : 1_000_000) : 3_600_000_000L)) {
                supervisor.start(new OutboundPeerConnection(initial,
                        new PeerAddress(InetAddress.getByName("1.1.1.1"), port, 9)));
                await(() -> supervisor.activeConnectionCount() == 3 && initialRequests.get() >= 2);
                if (fractional) {
                    long deadline = clock.get() + 125_000;
                    supervisor.checkRecoveryAtMicros(deadline - 1, false);
                    assertEquals(2, attempts.get());
                    supervisor.checkRecoveryAtMicros(deadline, false);
                    assertEquals(2, attempts.get());
                    supervisor.checkRecoveryAtMicros(deadline + 1, false);
                    await(() -> supervisor.activeConnectionCount() == 4);
                    assertEquals(3, attempts.get());
                    return;
                }
                var prior = supervisor.connections().stream().map(OutboundPeerConnection::peer).toList();
                long now = System.currentTimeMillis() / 1000;
                supervisor.checkRecovery(now + 2);
                await(() -> attempts.get() == 3);
                if (sharedBudget) {
                    assertTrue(probeEntered.await(2, TimeUnit.SECONDS));
                    stale.set(true);
                    supervisor.checkRecovery(now + 603);
                    assertFalse(fullStarted.await(100, TimeUnit.MILLISECONDS), "Full probe must wait for the pending block connection");
                    releaseProbe.countDown();
                    await(() -> supervisor.activeConnectionCount() == 4);
                    supervisor.checkRecovery(now + 604);
                    assertTrue(fullStarted.await(2, TimeUnit.SECONDS));
                    await(() -> supervisor.activeConnectionCount() == 4);
                    assertEquals(4, attempts.get());
                    return;
                }
                if (failProbe) {
                    supervisor.checkRecovery(now + 34);
                    assertEquals(3, supervisor.activeConnectionCount());
                    assertEquals(3, attempts.get());
                    return;
                }
                await(() -> supervisor.activeConnectionCount() == 4 && initialRequests.get() >= 4);
                var extra = supervisor.connections().stream().filter(c -> !prior.contains(c.peer())).findFirst().orElseThrow();
                assertEquals(PeerConnectionRole.BLOCK_RELAY_ONLY, extra.role());
                if (useful) extra.peer().recordAcceptedBlock();
                downloading.set(true);
                supervisor.checkRecovery(now + 34);
                assertEquals(4, supervisor.activeConnectionCount());
                downloading.set(false);
                supervisor.checkRecovery(now + 35);
                await(() -> supervisor.activeConnectionCount() == 3);
                assertTrue(initial.isReady());
                assertEquals(useful, extra.peer().isReady());
                if (useful) {
                    extra.peer().close();
                    await(() -> attempts.get() == 4 && supervisor.activeConnectionCount() == 3);
                }
                assertEquals(useful ? 4 : 3, attempts.get());
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
