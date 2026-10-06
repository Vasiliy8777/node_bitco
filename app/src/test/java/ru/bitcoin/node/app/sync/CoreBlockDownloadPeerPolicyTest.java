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
