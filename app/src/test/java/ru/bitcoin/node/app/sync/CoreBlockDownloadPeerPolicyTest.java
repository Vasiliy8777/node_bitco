package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreBlockDownloadPeerPolicyTest {
    @Test
    void missingLocatorAncestryIsFatalInsteadOfBeingIgnoredAsPeerFailure() throws Exception {
        var tip = extend(genesis, 3, 1); headers.consider(tip);
        var missing = ancestor(tip, 1).hash();
        var broken = new BlockLocatorBuilder(hash -> hash.equals(missing) ? null : indexes.get(hash));
        var actual = new CoreBlockDownloadPeerPolicy(lookup, active::get, headers, broken,
                NetworkParametersRegistry.regtest(), failed::contains, ibd::get, snapshot::get);
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        var failure = assertThrows(IllegalStateException.class, () -> actual.refresh(List.of(peer)));
        assertTrue(failure.getMessage().contains("Missing ancestor"));
        verify(peer, never()).sendAsync(any());
        verify(peer, never()).disconnectForProtocolViolation(anyString(), any());
        verify(peer, never()).close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void availabilitySendStateFailureIsFatalUnlessConnectionClosed(boolean closed) throws Exception {
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        when(peer.state()).thenReturn(ru.bitcoin.node.p2p.PeerState.READY);
        var requests = mock(CoreBlockDownloadPeerPolicy.HeadersRequests.class);
        var failure = new IllegalStateException("Synthetic internal send state failure");
        when(requests.trySend(eq(peer), any())).thenAnswer(call -> {
            if (closed) when(peer.state()).thenReturn(ru.bitcoin.node.p2p.PeerState.CLOSED);
            throw failure;
        });
        var actual = new CoreBlockDownloadPeerPolicy(lookup, active::get, headers, new BlockLocatorBuilder(lookup),
                NetworkParametersRegistry.regtest(), failed::contains, ibd::get, snapshot::get, source -> true, requests);
        if (closed) assertDoesNotThrow(() -> actual.refresh(List.of(peer)));
        else assertSame(failure, assertThrows(IllegalStateException.class, () -> actual.refresh(List.of(peer))));
        verify(peer, never()).disconnectForProtocolViolation(anyString(), any());
        verify(peer, never()).close();
    }

    @Test
    void transportFailureRemainsLocalAndDoesNotDelayTheNextAvailabilityAttempt() throws Exception {
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        var requests = mock(CoreBlockDownloadPeerPolicy.HeadersRequests.class);
        when(requests.trySend(eq(peer), any())).thenThrow(new java.io.IOException("Synthetic transport failure")).thenReturn(true);
        var actual = new CoreBlockDownloadPeerPolicy(lookup, active::get, headers, new BlockLocatorBuilder(lookup),
                NetworkParametersRegistry.regtest(), failed::contains, ibd::get, snapshot::get, source -> true, requests);
        assertDoesNotThrow(() -> actual.refresh(List.of(peer)));
        assertDoesNotThrow(() -> actual.refresh(List.of(peer)));
        verify(requests, times(2)).trySend(eq(peer), any());
        verify(peer, never()).disconnectForProtocolViolation(anyString(), any());
    }
    @Test
    void availabilityDeferralDoesNotDelayRetryOrBypassInitialSourceAdmission() throws Exception {
        var tip = extend(genesis, 4, 1); headers.consider(tip);
        var first = peer(VersionMessage.DEFAULT_SERVICES); var second = peer(VersionMessage.DEFAULT_SERVICES);
        var send = new AtomicBoolean();
        when(first.trySendGetHeadersAsync(any())).thenAnswer(call -> send.get());
        when(second.trySendGetHeadersAsync(any())).thenReturn(true);
        var clock = new AtomicLong(1_800_000_000_000_000L);
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            peers.add(first);
            var actual = managedAvailability(peers, clock);
            actual.refresh(peers.readyPeers());
            assertEquals(0, peers.headersSyncStartedCount());
            send.set(true);
            actual.refresh(peers.readyPeers());
            verify(first, times(2)).trySendGetHeadersAsync(any());
            assertTrue(peers.headersSyncStarted(first));
            peers.add(second);
            actual.refresh(peers.readyPeers());
            assertFalse(peers.headersSyncStarted(second));
            verify(second, never()).trySendGetHeadersAsync(any());
            peers.remove(first);
            actual.refresh(peers.readyPeers());
            assertTrue(peers.headersSyncStarted(second), "A deferred peer must retry without an extra two-minute cooldown");
            verify(first, never()).trySendGetHeaders(any());
            verify(second, never()).sendAsync(argThat(message -> message.command().equals("getheaders")));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void availabilityChecksInitialDeadlineEvenWhileItsProbeCooldownIsPending(boolean noBan) throws Exception {
        var tip = extend(genesis, 4, 1); headers.consider(tip);
        var first = peer(VersionMessage.DEFAULT_SERVICES);
        when(first.hasPermission(ru.bitcoin.node.p2p.PeerPermission.NOBAN)).thenReturn(noBan);
        when(first.trySendGetHeadersAsync(any())).thenReturn(true);
        var clock = new AtomicLong(1_800_000_000_000_000L);
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            peers.add(first);
            doAnswer(call -> { peers.remove(first); return null; }).when(first).close();
            var actual = managedAvailability(peers, clock);
            actual.refresh(peers.readyPeers());
            peers.add(peer(VersionMessage.DEFAULT_SERVICES));
            long deadline = ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.initialDeadline(
                    clock.get(), clock.get() / 1_000_000, tip.header().timestamp().value(), 600);
            clock.set(deadline);
            actual.refresh(List.of(first));
            verify(first, never()).close();
            clock.incrementAndGet();
            actual.refresh(List.of(first));
            verify(first, noBan ? never() : times(1)).close();
            assertFalse(peers.headersSyncStarted(first));
            verify(first, times(1)).trySendGetHeadersAsync(any());
            if (noBan) {
                actual.refresh(List.of(first));
                assertTrue(peers.headersSyncStarted(first));
                verify(first, times(2)).trySendGetHeadersAsync(any());
            }
        }
    }

    private CoreBlockDownloadPeerPolicy managedAvailability(ru.bitcoin.node.p2p.PeerManager peers, AtomicLong clock) {
        var locators = new BlockLocatorBuilder(lookup);
        return new CoreBlockDownloadPeerPolicy(lookup, active::get, headers, locators,
                NetworkParametersRegistry.regtest(), failed::contains, ibd::get, snapshot::get,
                source -> peers.canSyncFrom(source, false),
                new CoreAvailabilityHeaders(peers, headers, locators, 600, () -> false, clock::get));
    }
    @Test
    void ibdUsesPreferredSourcesWithInboundFallbackAndOneDecisionPerPass() throws Exception {
        var tip = extend(genesis, 4, 1);
        var outbound = peer(VersionMessage.DEFAULT_SERVICES);
        var inbound = peer(VersionMessage.DEFAULT_SERVICES);
        var noBan = peer(VersionMessage.DEFAULT_SERVICES);
        var fetch = peer(VersionMessage.DEFAULT_SERVICES);
        var limited = peer(VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS);
        var client = peer(VersionMessage.NODE_WITNESS);
        when(inbound.isInboundConnection()).thenReturn(true);
        when(noBan.isInboundConnection()).thenReturn(true);
        when(noBan.hasPermission(ru.bitcoin.node.p2p.PeerPermission.NOBAN)).thenReturn(true);
        var inFlight = new AtomicBoolean(true);
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            for (var source : List.of(outbound, inbound, noBan, limited, client)) peers.add(source);
            peers.add(fetch, ru.bitcoin.node.p2p.PeerConnectionRole.ADDR_FETCH);
            for (var source : peers.readyPeers()) when(source.lastBlockAnnouncement()).thenReturn(tip.hash());
            var actual = new CoreBlockDownloadPeerPolicy(lookup, active::get, headers, new BlockLocatorBuilder(lookup),
                    NetworkParametersRegistry.regtest(), failed::contains, ibd::get, snapshot::get,
                    source -> peers.canSyncFrom(source, inFlight.get()));
            actual.refresh(peers.readyPeers());
            assertTrue(actual.canDownload(outbound, tip.hash(), 4L));
            assertTrue(actual.canDownload(noBan, tip.hash(), 4L));
            assertFalse(actual.canDownload(inbound, tip.hash(), 4L));
            assertFalse(actual.canTraverse(inbound, tip.hash(), 4L), "Ineligible sources must not become stall candidates");
            assertFalse(actual.canDownload(fetch, tip.hash(), 4L));
            assertFalse(actual.canDownload(limited, tip.hash(), 4L));
            assertFalse(actual.canDownload(client, tip.hash(), 4L));
            inFlight.set(false);
            actual.refresh(peers.readyPeers());
            assertTrue(actual.canDownload(inbound, tip.hash(), 4L));
            inFlight.set(true);
            assertTrue(actual.canDownload(inbound, tip.hash(), 4L), "First assignment must not disable the rest of this batch");
            actual.refresh(peers.readyPeers());
            assertFalse(actual.canDownload(inbound, tip.hash(), 4L));
            ibd.set(false);
            assertTrue(actual.canDownload(inbound, tip.hash(), 4L));
            assertTrue(actual.canDownload(fetch, tip.hash(), 4L));
            assertTrue(actual.canDownload(limited, tip.hash(), 4L));
            ibd.set(true);
            peers.remove(outbound); peers.remove(noBan); peers.remove(limited);
            actual.refresh(peers.readyPeers());
            assertTrue(actual.canDownload(inbound, tip.hash(), 4L), "No preferred sources: inbound fallback remains available");
            assertFalse(actual.canDownload(fetch, tip.hash(), 4L));
        }
    }
    private final Map<Hash256, BlockIndex> indexes = new HashMap<>();
    private final BlockIndex genesis = BlockIndexFactory.createGenesis(
            GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
    private final AtomicReference<BlockIndex> active = new AtomicReference<>(genesis);
    private final AtomicBoolean ibd = new AtomicBoolean(true);
    private final AtomicReference<BlockIndex> snapshot = new AtomicReference<>();
    private final Set<BlockIndex> failed = new HashSet<>();
    private final HeaderChainState headers = new HeaderChainState(genesis);
    private final BlockIndexLookup lookup = indexes::get;
    private final CoreBlockDownloadPeerPolicy policy = new CoreBlockDownloadPeerPolicy(lookup, active::get,
            headers, new BlockLocatorBuilder(lookup), NetworkParametersRegistry.regtest(),
            failed::contains, ibd::get, snapshot::get);

    CoreBlockDownloadPeerPolicyTest() { indexes.put(genesis.hash(), genesis); }

    @Test
    void slowRefreshPublishesAtomicallyWithoutBlockingExistingEligibility() throws Exception {
        var tip = extend(genesis, 32, 1);
        var fork = extend(genesis, 33, 1000);
        var requested = ancestor(tip, 16);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        BlockIndexLookup delayed = hash -> {
            if (hash.equals(fork.hash())) {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("Refresh not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return indexes.get(hash);
        };
        var concurrent = new CoreBlockDownloadPeerPolicy(delayed, active::get, headers,
                new BlockLocatorBuilder(delayed), NetworkParametersRegistry.regtest(),
                failed::contains, ibd::get, snapshot::get);
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
        concurrent.refresh(List.of(peer));
        assertTrue(concurrent.canDownload(peer, requested.hash(), requested.height()));
        when(peer.lastBlockAnnouncement()).thenReturn(fork.hash());
        var refreshing = java.util.concurrent.CompletableFuture.runAsync(() -> concurrent.refresh(List.of(peer)));
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var decision = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> concurrent.canDownload(peer, requested.hash(), requested.height()));
            assertTrue(decision.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); }
        refreshing.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(concurrent.canDownload(peer, requested.hash(), requested.height()));
        assertTrue(concurrent.canDownload(peer, fork.hash(), fork.height()));
    }

    @Test
    void buildsAvailabilityLocatorOnceForPeersAndRebuildsForNewTip() throws Exception {
        var tip = extend(genesis, 64, 1);
        headers.consider(tip);
        var builder = spy(new BlockLocatorBuilder(lookup));
        var cachedPolicy = new CoreBlockDownloadPeerPolicy(lookup, active::get, headers,
                builder, NetworkParametersRegistry.regtest(), failed::contains,
                ibd::get, snapshot::get);
        var peers = new ArrayList<Peer>();
        for (int i = 0; i < 8; i++) peers.add(peer(VersionMessage.DEFAULT_SERVICES));
        cachedPolicy.refresh(peers);
        verify(builder, times(1)).build(ancestor(tip, 63));
        for (var peer : peers) verify(peer, times(1)).sendAsync(any());
        cachedPolicy.refresh(List.of(peer(VersionMessage.DEFAULT_SERVICES)));
        verify(builder, times(1)).build(any());
        var next = extend(tip, 1, 200);
        headers.consider(next);
        cachedPolicy.refresh(List.of(peer(VersionMessage.DEFAULT_SERVICES)));
        verify(builder, times(1)).build(tip);
        verify(builder, times(2)).build(any());
    }

    @Test
    void sharesImmutableAncestryAcrossPeersButRechecksForksAndFailures() {
        var tip = extend(genesis, 64, 1);
        var other = extend(genesis, 65, 1000);
        var requested = ancestor(tip, 32);
        var calls = new AtomicInteger();
        BlockIndexAncestorLookup counted = new BlockIndexAncestorLookup() {
            @Override public BlockIndex find(Hash256 hash) { return indexes.get(hash); }
            @Override public BlockIndex ancestor(BlockIndex index, long height) {
                calls.incrementAndGet();
                return CoreBlockDownloadPeerPolicyTest.this.ancestor(index, height);
            }
        };
        var cachedPolicy = new CoreBlockDownloadPeerPolicy(counted, active::get, headers,
                new BlockLocatorBuilder(counted), NetworkParametersRegistry.regtest(),
                failed::contains, ibd::get, snapshot::get);
        var peers = new ArrayList<Peer>();
        for (int i = 0; i < 8; i++) {
            var peer = peer(VersionMessage.DEFAULT_SERVICES);
            when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
            peers.add(peer);
        }
        cachedPolicy.refresh(peers);
        int baseline = calls.get();
        for (int repeat = 0; repeat < 10; repeat++) {
            for (var peer : peers)
                assertTrue(cachedPolicy.canDownload(peer, requested.hash(), requested.height()));
        }
        assertEquals(1, calls.get() - baseline, "80 decisions need one ancestry traversal");
        active.set(requested);
        cachedPolicy.refresh(peers);
        assertEquals(1, calls.get() - baseline, "the committed frontier has already been proven");
        var forkAtHeight = ancestor(other, 32);
        assertFalse(cachedPolicy.canServe(peers.getFirst(), forkAtHeight.hash(), 32L));
        failed.add(requested);
        assertFalse(cachedPolicy.canServe(peers.getFirst(), requested.hash(), 32L));
        failed.clear();
        when(peers.getFirst().lastBlockAnnouncement()).thenReturn(other.hash());
        cachedPolicy.refresh(peers);
        assertFalse(cachedPolicy.canServe(peers.getFirst(), requested.hash(), 32L));
        assertTrue(cachedPolicy.canServe(peers.getFirst(), forkAtHeight.hash(), 32L));
        assertTrue(cachedPolicy.canServe(peers.getLast(), requested.hash(), 32L));
    }

    @Test
    void requiresValidatedAnnouncementInsteadOfVersionHeight() throws Exception {
        var tip = extend(genesis, 3, 1);
        headers.consider(tip);
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        policy.refresh(List.of(peer));
        assertFalse(policy.canDownload(peer, tip.hash(), tip.height()));
        when(peer.lastBlockAnnouncement()).thenReturn(new Hash256(new byte[32]));
        policy.refresh(List.of(peer));
        assertFalse(policy.canDownload(peer, tip.hash(), tip.height()));
        when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
        policy.refresh(List.of(peer));
        assertTrue(policy.canDownload(peer, tip.hash(), tip.height()));
        verify(peer, times(1)).sendAsync(any());
    }

    @Test
    void keepsWindowPlusOneAvailableOnlyForStallProbeAndAdvancesAfterCommit() {
        var tip = extend(genesis, 1025, 1);
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
        policy.refresh(List.of(peer));
        var boundary = lookup.find(tip.previousBlockHash());
        assertTrue(policy.canDownload(peer, boundary.hash(), 1024L));
        assertFalse(policy.canDownload(peer, tip.hash(), 1025L));
        assertTrue(policy.canServe(peer, tip.hash(), 1025L));
        assertTrue(policy.canProbeWindowEnd(peer, tip.hash(), 1025L));
        active.set(ancestor(tip, 1));
        policy.refresh(List.of(peer));
        assertTrue(policy.canDownload(peer, tip.hash(), 1025L));
        assertFalse(policy.canProbeWindowEnd(peer, tip.hash(), 1025L),
                "work inside the peer's advanced window must not start a stall timer");
    }

    @Test
    void rejectsOtherBranchAndLowerWorkEvenIfPeerClaimsHighHeight() {
        var tip = extend(genesis, 3, 1);
        var other = extend(genesis, 2, 100);
        var peer = peer(VersionMessage.DEFAULT_SERVICES);
        when(peer.lastBlockAnnouncement()).thenReturn(other.hash());
        policy.refresh(List.of(peer));
        assertFalse(policy.canServe(peer, tip.hash(), tip.height()));
        active.set(tip);
        policy.refresh(List.of(peer));
        assertFalse(policy.canServe(peer, other.hash(), other.height()));
    }

    @Test
    void excludesLimitedPeersDuringIbdAndUsesValidatedTipWithTwoBlockBuffer() {
        var tip = extend(genesis, 300, 1);
        var peer = peer(VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS);
        when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
        policy.refresh(List.of(peer));
        assertFalse(policy.canServe(peer, tip.hash(), 300L));
        ibd.set(false);
        assertTrue(policy.canServe(peer, ancestor(tip, 15).hash(), 15L));
        assertFalse(policy.canServe(peer, ancestor(tip, 14).hash(), 14L));
    }

    @Test
    void enforcesWitnessFailedBranchAndUnvalidatedSnapshotAncestry() {
        var tip = extend(genesis, 2, 1);
        var peer = peer(VersionMessage.NODE_NETWORK);
        when(peer.lastBlockAnnouncement()).thenReturn(tip.hash());
        policy.refresh(List.of(peer));
        assertFalse(policy.canServe(peer, tip.hash(), 2L));
        when(peer.remoteVersion().services()).thenReturn(VersionMessage.DEFAULT_SERVICES);
        assertTrue(policy.canServe(peer, tip.hash(), 2L));
        snapshot.set(extend(genesis, 1, 100));
        assertFalse(policy.canServe(peer, tip.hash(), 2L));
        snapshot.set(ancestor(tip, 1));
        assertTrue(policy.canServe(peer, tip.hash(), 2L));
        failed.add(tip);
        assertFalse(policy.canServe(peer, tip.hash(), 2L));
    }

    private Peer peer(long services) {
        var peer = mock(Peer.class);
        var version = mock(VersionMessage.class);
        when(peer.isReady()).thenReturn(true);
        when(peer.remoteVersion()).thenReturn(version);
        when(version.services()).thenReturn(services);
        when(version.version()).thenReturn(VersionMessage.CURRENT_PROTOCOL_VERSION);
        when(version.startHeight()).thenReturn(Integer.MAX_VALUE);
        return peer;
    }

    private BlockIndex extend(BlockIndex parent, int count, int salt) {
        for (int i = 0; i < count; i++) {
            var header = new BlockHeader(4, parent.hash(), genesis.hash(),
                    new UInt32(parent.header().timestamp().value() + 1), parent.header().bits(), new UInt32(salt + i));
            parent = BlockIndexFactory.createChild(parent, header);
            indexes.put(parent.hash(), parent);
        }
        return parent;
    }

    private BlockIndex ancestor(BlockIndex tip, long height) {
        while (tip.height() > height) tip = lookup.find(tip.previousBlockHash());
        return tip;
    }
}
