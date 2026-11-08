package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import ru.bitcoin.node.common.types.Hash256;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.p2p.PeerChainSyncPolicy.Action.*;

class PeerChainSyncPolicyTest {
    @Test void finalProbeKeepsOriginalBenchmarkLocatorUntilPeerReachesItsWork() {
        var policy = new PeerChainSyncPolicy();
        var oldLocator = List.of(Hash256.fromDisplayHex("11".repeat(32)));
        var newLocator = List.of(Hash256.fromDisplayHex("22".repeat(32)));
        policy.check(1000, false, new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(100), BigInteger.ONE, oldLocator));
        assertEquals(REQUEST_HEADERS, policy.check(2201, false,
                new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(200), BigInteger.valueOf(99), newLocator)));
        assertEquals(oldLocator, policy.locator());
        assertEquals(NONE, policy.check(2202, false,
                new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(200), BigInteger.valueOf(100), newLocator)));
        assertEquals(NONE, policy.check(3402, false,
                new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(200), BigInteger.valueOf(100), newLocator)));
        assertEquals(REQUEST_HEADERS, policy.check(3403, false,
                new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(200), BigInteger.valueOf(100), newLocator)));
        assertEquals(newLocator, policy.locator());
    }
    private static OutboundPeerRecoveryView.ChainWork work(long tip, Long peer) {
        return new OutboundPeerRecoveryView.ChainWork(BigInteger.valueOf(tip),
                peer == null ? null : BigInteger.valueOf(peer), List.of(Hash256.fromDisplayHex("01".repeat(32))));
    }
    @Test void asksAfterTwentyMinutesThenDisconnectsAfterTwoMoreWithStrictBoundaries() {
        var policy = new PeerChainSyncPolicy();
        assertEquals(NONE, policy.check(1000, false, work(100, 10L)));
        assertEquals(NONE, policy.check(2200, false, work(100, 10L)));
        assertEquals(REQUEST_HEADERS, policy.check(2201, false, work(100, 10L)));
        assertEquals(work(100, 10L).locator(), policy.locator());
        assertEquals(NONE, policy.check(2321, false, work(100, 10L)));
        assertEquals(DISCONNECT, policy.check(2322, false, work(100, 10L)));
    }
    @Test void catchingUpCancelsFinalDisconnectAndAChangedTipStartsANewDeadline() {
        var policy = new PeerChainSyncPolicy();
        policy.check(1000, false, work(100, 1L));
        assertEquals(REQUEST_HEADERS, policy.check(2201, false, work(100, 1L)));
        assertEquals(NONE, policy.check(2300, false, work(100, 100L)));
        assertEquals(NONE, policy.check(2400, false, work(200, 100L)));
        assertEquals(NONE, policy.check(3600, false, work(200, 100L)));
        assertEquals(REQUEST_HEADERS, policy.check(3601, false, work(200, 100L)));
    }
    @Test void reachingOldBenchmarkRestartsTimeoutAgainstTheNewTip() {
        var policy = new PeerChainSyncPolicy();
        policy.check(1000, false, work(100, 1L));
        assertEquals(NONE, policy.check(2201, false, work(200, 100L)));
        assertEquals(NONE, policy.check(3401, false, work(200, 150L)));
        assertEquals(REQUEST_HEADERS, policy.check(3402, false, work(200, 150L)));
    }
    @Test void arbitraryProgressBelowBenchmarkDoesNotRestartTimeout() {
        var policy = new PeerChainSyncPolicy();
        policy.check(1000, false, work(100, 1L));
        assertEquals(REQUEST_HEADERS, policy.check(2201, false, work(200, 99L)));
    }
    @Test void protectedPeersAndMissingStateAreNotEvicted() {
        var policy = new PeerChainSyncPolicy();
        policy.check(1000, false, work(100, null));
        assertEquals(NONE, policy.check(3000, true, work(100, null)));
        assertEquals(NONE, policy.check(3000, false, null));
        assertEquals(REQUEST_HEADERS, policy.check(3000, false, work(100, null)));
    }
}
