package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreIbdPolicyContractTest {

    @Test
    void usesCoreHeaderResponseDeadline() {
        assertEquals(java.time.Duration.ofMinutes(2), HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT);
    }

    @Test
    void usesCoreDownloadTimeoutWithParallelPeerAllowance() {
        var policy = new BlockDownloadTimeoutPolicy(java.time.Duration.ofMinutes(10));
        assertEquals(java.time.Duration.ofMinutes(10), policy.timeout(0));
        assertEquals(java.time.Duration.ofMinutes(15), policy.timeout(1));
        assertEquals(java.time.Duration.ofMinutes(55), policy.timeout(9));
    }

    @Test
    void refusesNonCorePerPeerPipelineDepth() throws Exception {
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new BlockDownloadScheduler(peers, new BlockDownloadService(peers),
                            new BlockDownloadTimeoutPolicy(java.time.Duration.ofMinutes(10)), 17));
        }
    }

    @Test
    void usesCorePerPeerTransitLimit() {
        assertEquals(16, BlockInFlightTracker.DEFAULT_MAX_BLOCKS_PER_PEER);
    }

    @Test
    void schedulerUsesCoreStyleSessionInsteadOfReplicatedFrontier() throws Exception {
        try (var peers = new ru.bitcoin.node.p2p.PeerManager()) {
            var scheduler = new BlockDownloadScheduler(
                    peers,
                    new BlockDownloadService(peers),
                    new BlockDownloadTimeoutPolicy(java.time.Duration.ofMinutes(10)),
                    16);
            try (var session = scheduler.openSession()) {
                assertEquals(
                        SchedulerBlockDownloadSession.class,
                        session.getClass(),
                        "production IBD must use the Core-style per-peer scheduler, not replicated frontier"
                );
            }
        }
    }
}
