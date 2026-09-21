package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadStallTimeoutEvaluatorTest {
    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();
    @Test
    void shouldReportNoStallWhenTrackerIsClear()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            BlockDownloadStallTimeoutEvaluator evaluator =
                    evaluator(
                            tracker
                    );

            BlockDownloadStallTimeoutEvaluator.Evaluation result =
                    evaluator.evaluate();

            assertFalse(
                    result.stalling()
            );

            assertFalse(
                    result.timedOut()
            );

            assertNull(
                    result.peer()
            );

            assertEquals(
                    Duration.ZERO,
                    result.stallingAge()
            );

            assertEquals(
                    Duration.ZERO,
                    result.timeout()
            );
        }
    }

    @Test
    void shouldReportTrackedPeerBeforeTimeout()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            tracker.update(
                    peer
            );

            now.set(
                    Duration.ofSeconds(1)
                            .toNanos()
            );

            BlockDownloadStallTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate();

            assertTrue(
                    result.stalling()
            );

            assertFalse(
                    result.timedOut()
            );

            assertSame(
                    peer,
                    result.peer()
            );

            assertEquals(
                    Duration.ofSeconds(1),
                    result.stallingAge()
            );

            assertEquals(
                    Duration.ofSeconds(2),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldNotTimeoutExactlyAtBoundary()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            tracker.update(
                    peer
            );

            now.set(
                    Duration.ofSeconds(2)
                            .toNanos()
            );

            BlockDownloadStallTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate();

            assertTrue(
                    result.stalling()
            );

            assertFalse(
                    result.timedOut()
            );

            assertSame(
                    peer,
                    result.peer()
            );

            assertEquals(
                    Duration.ofSeconds(2),
                    result.stallingAge()
            );

            assertEquals(
                    Duration.ofSeconds(2),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldReportTimeoutAfterBoundary()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            tracker.update(
                    peer
            );

            now.set(
                    Duration.ofSeconds(2)
                            .plusNanos(1)
                            .toNanos()
            );

            BlockDownloadStallTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate();

            assertTrue(
                    result.stalling()
            );

            assertTrue(
                    result.timedOut()
            );

            assertSame(
                    peer,
                    result.peer()
            );

            assertEquals(
                    Duration.ofSeconds(2)
                            .plusNanos(1),
                    result.stallingAge()
            );

            assertEquals(
                    Duration.ofSeconds(2),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldUseIncreasedPolicyTimeout()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            BlockDownloadStallTimeoutPolicy policy =
                    new BlockDownloadStallTimeoutPolicy();

            policy.increaseAfterTimeout();

            BlockDownloadStallTimeoutEvaluator evaluator =
                    new BlockDownloadStallTimeoutEvaluator(
                            tracker,
                            policy
                    );

            tracker.update(
                    peer
            );

            now.set(
                    Duration.ofSeconds(3)
                            .toNanos()
            );

            BlockDownloadStallTimeoutEvaluator.Evaluation result =
                    evaluator.evaluate();

            assertTrue(
                    result.stalling()
            );

            assertFalse(
                    result.timedOut()
            );

            assertSame(
                    peer,
                    result.peer()
            );

            assertEquals(
                    Duration.ofSeconds(3),
                    result.stallingAge()
            );

            assertEquals(
                    Duration.ofSeconds(4),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldIncreaseTimeoutOnlyWhenTimeoutIsHandled()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockDownloadStallTracker tracker =
                    tracker(now);

            BlockDownloadStallTimeoutPolicy policy =
                    new BlockDownloadStallTimeoutPolicy();

            BlockDownloadStallTimeoutEvaluator evaluator =
                    new BlockDownloadStallTimeoutEvaluator(
                            tracker,
                            policy
                    );

            tracker.update(
                    peer
            );

            now.set(
                    Duration.ofSeconds(2)
                            .plusNanos(1)
                            .toNanos()
            );

            BlockDownloadStallTimeoutEvaluator.Evaluation before =
                    evaluator.evaluate();

            assertTrue(
                    before.timedOut()
            );

            assertEquals(
                    Duration.ofSeconds(2),
                    before.timeout()
            );

            evaluator.timeoutHandled();

            BlockDownloadStallTimeoutEvaluator.Evaluation after =
                    evaluator.evaluate();

            assertFalse(
                    after.timedOut()
            );

            assertEquals(
                    Duration.ofSeconds(4),
                    after.timeout()
            );
        }
    }

    private static BlockDownloadStallTracker tracker(
            AtomicLong now
    ) {

        return new BlockDownloadStallTracker(
                now::get
        );
    }

    private static BlockDownloadStallTimeoutEvaluator evaluator(
            BlockDownloadStallTracker tracker
    ) {

        return new BlockDownloadStallTimeoutEvaluator(
                tracker,
                new BlockDownloadStallTimeoutPolicy()
        );
    }

    private static Peer peer() {

        PeerConnection connection =
                new PeerConnection(
                        PARAMETERS,
                        5_000,
                        5_000
                );

        return new Peer(
                connection,
                VersionMessage.DEFAULT_SERVICES,
                0,
                true
        );
    }
}