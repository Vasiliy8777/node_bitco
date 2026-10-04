package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreIbdPolicyContractTest {
    @Test
    void usesCorePerPeerTransitLimit() {
        assertEquals(32, BlockInFlightTracker.DEFAULT_MAX_BLOCKS_PER_PEER);
    }

    @Test
    void replicatedFrontierUsesEightEqual128BlockCaches() {
        assertEquals(128, ReplicatedFrontierBlockDownloadSession.CACHE_BLOCKS_PER_PEER);
        assertEquals(8, ReplicatedFrontierBlockDownloadSession.MAX_CACHE_PEERS);
        assertEquals(1024,
                ReplicatedFrontierBlockDownloadSession.CACHE_BLOCKS_PER_PEER
                        * ReplicatedFrontierBlockDownloadSession.MAX_CACHE_PEERS);
    }

    @Test
    void replicatedFrontierRejectsMoreThan128UnfinishedLogicalBlocks() throws Exception {
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            var scheduler = new BlockDownloadScheduler(
                    peers,
                    new BlockDownloadService(peers),
                    new BlockDownloadTimeoutPolicy(java.time.Duration.ofMinutes(10)),
                    ReplicatedFrontierBlockDownloadSession.CACHE_BLOCKS_PER_PEER);
            try (var session = scheduler.openSession()) {
                java.util.List<ru.bitcoin.node.common.types.Hash256> hashes = new java.util.ArrayList<>();
                for (int i = 0; i < 129; i++) {
                    byte[] bytes = new byte[32];
                    bytes[0] = (byte) i;
                    bytes[1] = (byte) (i >>> 8);
                    hashes.add(new ru.bitcoin.node.common.types.Hash256(bytes));
                }
                assertThrows(java.io.IOException.class, () -> session.submit(hashes));
                assertEquals(0, session.pendingCount());
            }
        }
    }
}
