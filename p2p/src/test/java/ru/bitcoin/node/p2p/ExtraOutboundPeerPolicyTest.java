package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExtraOutboundPeerPolicyTest {
    @Test void graceBoundariesDifferByOneMicrosecondForFullAndBlockRelay() {
        long connected = 100_750_123;
        var full = List.of(new ExtraOutboundPeerPolicy.Candidate(1, 1, 0, 0, false, false),
                new ExtraOutboundPeerPolicy.Candidate(2, 1, connected, 0, false, false));
        var blocks = List.of(new ExtraOutboundPeerPolicy.BlockCandidate(1, 0, 0, false),
                new ExtraOutboundPeerPolicy.BlockCandidate(2, connected, 0, false));
        assertTrue(ExtraOutboundPeerPolicy.evictionCandidate(full, connected + 29_999_999).isEmpty());
        assertTrue(ExtraOutboundPeerPolicy.blockEvictionCandidate(blocks, connected + 29_999_999).isEmpty());
        assertTrue(ExtraOutboundPeerPolicy.evictionCandidate(full, connected + 30_000_000).isEmpty());
        assertEquals(2, ExtraOutboundPeerPolicy.blockEvictionCandidate(blocks, connected + 30_000_000).orElseThrow());
        assertEquals(2, ExtraOutboundPeerPolicy.evictionCandidate(full, connected + 30_000_001).orElseThrow());
    }

    @Test void fractionalConnectionTimeCannotEvictBeforeThirtySeconds() {
        var blocks = List.of(new ExtraOutboundPeerPolicy.BlockCandidate(1, 0, 0, false),
                new ExtraOutboundPeerPolicy.BlockCandidate(2, 100_750_000, 0, false));
        assertTrue(ExtraOutboundPeerPolicy.blockEvictionCandidate(blocks, 130_500_000).isEmpty());
    }

    @Test void exponentialSamplesRetainFiveMinuteMeanWithoutWholeSecondQuantization() {
        long sum = 0; int fractional = 0;
        for (int i = 0; i < 100_000; i++) {
            long delay = ExtraOutboundPeerPolicy.probeDelayMicros((i + .5) / 100_000);
            sum += delay;
            if (delay % 1_000_000 != 0) fractional++;
        }
        assertEquals(300_000_000, sum / 100_000.0, 30_000);
        assertTrue(fractional > 99_000);
    }
    @Test void blockRelayEvictsYoungestUnlessItDeliveredAMoreRecentBlock() {
        var old = new ExtraOutboundPeerPolicy.BlockCandidate(1, 100_000_000, 999, false);
        var second = new ExtraOutboundPeerPolicy.BlockCandidate(2, 100_000_000, 10, false);
        assertEquals(3, ExtraOutboundPeerPolicy.blockEvictionCandidate(List.of(old, second,
                new ExtraOutboundPeerPolicy.BlockCandidate(3, 970_000_000, 10, false)), 1_000_000_000).orElseThrow());
        assertEquals(2, ExtraOutboundPeerPolicy.blockEvictionCandidate(List.of(old, second,
                new ExtraOutboundPeerPolicy.BlockCandidate(3, 990_000_000, 11, false)), 1_000_000_000).orElseThrow());
        assertTrue(ExtraOutboundPeerPolicy.blockEvictionCandidate(List.of(old, second,
                new ExtraOutboundPeerPolicy.BlockCandidate(3, 971_000_000, 0, false)), 1_000_000_000).isEmpty());
        assertTrue(ExtraOutboundPeerPolicy.blockEvictionCandidate(List.of(old,
                new ExtraOutboundPeerPolicy.BlockCandidate(2, 100_000_000, 10, true),
                new ExtraOutboundPeerPolicy.BlockCandidate(3, 100_000_000, 11, false)), 1_000_000_000).isEmpty());
    }

    @Test void blockProbeDelayUsesAnExponentialFiveMinuteMean() {
        assertEquals(0, ExtraOutboundPeerPolicy.probeDelayMicros(0));
        assertEquals(207_944_154, ExtraOutboundPeerPolicy.probeDelayMicros(.5));
        assertEquals(690_775_528, ExtraOutboundPeerPolicy.probeDelayMicros(.9));
        assertEquals(300, ExtraOutboundPeerPolicy.probeDelayMicros(.000001));
        assertThrows(IllegalArgumentException.class, () -> ExtraOutboundPeerPolicy.probeDelayMicros(1));
        assertThrows(IllegalArgumentException.class, () -> ExtraOutboundPeerPolicy.probeDelayMicros(Double.NaN));
    }
    @Test void staleThresholdAndTenMinuteChecksAreStrict() {
        var policy = new ExtraOutboundPeerPolicy(600);
        assertFalse(policy.check(1000, 1000, false, true));
        assertFalse(policy.check(2800, 1000, false, true)); // exactly three spacings
        assertFalse(policy.check(2801, 1000, false, true)); // timer not due
        assertTrue(policy.check(3401, 1000, false, true));
        policy.evicted();
        assertFalse(policy.check(3446, 1000, false, true));
        assertTrue(policy.check(4002, 1000, false, true));
        assertFalse(policy.check(4603, 4500, false, true));
    }

    @Test void initializesFromFirstCheckRatherThanOldBlockTimestamp() {
        var policy = new ExtraOutboundPeerPolicy(60);
        assertFalse(policy.check(10000, 0, false, true));
        assertTrue(policy.check(10601, 0, false, true));
    }

    @Test void activeDownloadsAndDisabledNetworkingPreventNewProbes() {
        var policy = new ExtraOutboundPeerPolicy(60);
        assertFalse(policy.check(1000, 1, true, true));
        assertFalse(policy.check(1601, 1, false, false));
        assertTrue(policy.check(2202, 1, false, true));
    }

    @Test void oldestAnnouncementWinsWithHigherConnectionIdBreakingTies() {
        assertEquals(2, ExtraOutboundPeerPolicy.evictionCandidate(List.of(
                peer(1, 1, 100, 500, false, false), peer(2, 1, 100, 500, false, false),
                peer(3, 1, 100, 600, false, false)), 1_000_000_000).orElseThrow());
    }

    @Test void protectsChainSyncPeersAndTheOnlyConnectionToANetwork() {
        assertEquals(3, ExtraOutboundPeerPolicy.evictionCandidate(List.of(
                peer(1, 1, 100, 0, true, false), peer(2, 2, 100, 0, false, false),
                peer(3, 1, 100, 100, false, false)), 1_000_000_000).orElseThrow());
    }

    @Test void doesNotSubstituteAnotherVictimWhenWorstPeerIsYoungOrDownloading() {
        var useful = peer(1, 1, 100, 50, false, false);
        assertTrue(ExtraOutboundPeerPolicy.evictionCandidate(List.of(useful,
                peer(2, 1, 970, 0, false, false)), 1_000_000_000).isEmpty());
        assertEquals(2, ExtraOutboundPeerPolicy.evictionCandidate(List.of(useful,
                peer(2, 1, 969, 0, false, false)), 1_000_000_000).orElseThrow());
        assertTrue(ExtraOutboundPeerPolicy.evictionCandidate(List.of(useful,
                peer(2, 1, 100, 0, false, true)), 1_000_000_000).isEmpty());
    }

    private static ExtraOutboundPeerPolicy.Candidate peer(long id, int network, long connected,
            long announcement, boolean protect, boolean downloading) {
        return new ExtraOutboundPeerPolicy.Candidate(id, network, connected * 1_000_000, announcement, protect, downloading);
    }
}
