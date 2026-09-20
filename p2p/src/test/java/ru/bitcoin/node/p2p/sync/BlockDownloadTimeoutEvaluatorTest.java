package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadTimeoutEvaluatorTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldReportPeerWithoutInflightBlocksAsNotDownloading()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            BlockDownloadTimeoutEvaluator evaluator =
                    evaluator(tracker);

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator.evaluate(
                            peer
                    );

            assertFalse(
                    result.downloading()
            );

            assertFalse(
                    result.timedOut()
            );

            assertEquals(
                    Duration.ZERO,
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ZERO,
                    result.timeout()
            );
        }
    }

    @Test
    void shouldReportActiveDownloadBeforeTimeout()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        Duration.ofMinutes(1)
                                .toNanos()
                );

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            tracker.register(
                    peer,
                    hash(1)
            );

            now.set(
                    Duration.ofMinutes(10)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate(
                                    peer
                            );

            assertTrue(
                    result.downloading()
            );

            assertFalse(
                    result.timedOut()
            );

            assertEquals(
                    Duration.ofMinutes(9),
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(10),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldNotTimeoutExactlyAtBoundary()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            tracker.register(
                    peer,
                    hash(1)
            );

            now.set(
                    Duration.ofMinutes(10)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate(
                                    peer
                            );

            assertTrue(
                    result.downloading()
            );

            assertFalse(
                    result.timedOut()
            );

            assertEquals(
                    Duration.ofMinutes(10),
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(10),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldReportTimedOutAfterBoundary()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            tracker.register(
                    peer,
                    hash(1)
            );

            now.set(
                    Duration.ofMinutes(10)
                            .plusNanos(1)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate(
                                    peer
                            );

            assertTrue(
                    result.downloading()
            );

            assertTrue(
                    result.timedOut()
            );

            assertEquals(
                    Duration.ofMinutes(10)
                            .plusNanos(1),
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(10),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldExtendTimeoutForOtherDownloadingPeers()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            tracker.register(
                    firstPeer,
                    hash(1)
            );

            tracker.register(
                    secondPeer,
                    hash(2)
            );

            /*
             * firstPeer has one OTHER downloading peer.
             *
             * 10 min * (1 + 0.5) = 15 min.
             */
            now.set(
                    Duration.ofMinutes(12)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate(
                                    firstPeer
                            );

            assertTrue(
                    result.downloading()
            );

            assertFalse(
                    result.timedOut()
            );

            assertEquals(
                    Duration.ofMinutes(12),
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(15),
                    result.timeout()
            );
        }
    }

    @Test
    void shouldRecalculateTimeoutWhenAnotherPeerStopsDownloading()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            Hash256 firstBlock =
                    hash(1);

            Hash256 secondBlock =
                    hash(2);

            tracker.register(
                    firstPeer,
                    firstBlock
            );

            tracker.register(
                    secondPeer,
                    secondBlock
            );

            now.set(
                    Duration.ofMinutes(12)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator evaluator =
                    evaluator(
                            tracker
                    );

            BlockDownloadTimeoutEvaluator.Evaluation before =
                    evaluator.evaluate(
                            firstPeer
                    );

            assertEquals(
                    Duration.ofMinutes(15),
                    before.timeout()
            );

            assertFalse(
                    before.timedOut()
            );

            /*
             * secondPeer is no longer downloading.
             * firstPeer is now the only downloading peer.
             */
            tracker.remove(
                    secondPeer,
                    secondBlock
            );

            BlockDownloadTimeoutEvaluator.Evaluation after =
                    evaluator.evaluate(
                            firstPeer
                    );

            assertEquals(
                    Duration.ofMinutes(10),
                    after.timeout()
            );

            assertEquals(
                    Duration.ofMinutes(12),
                    after.downloadingAge()
            );

            assertTrue(
                    after.timedOut()
            );
        }
    }

    @Test
    void shouldUseAdvancedDownloadingSinceAfterFirstBlockCompletes()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(now);

            Hash256 first =
                    hash(1);

            Hash256 second =
                    hash(2);

            tracker.register(
                    peer,
                    first
            );

            now.set(
                    Duration.ofMinutes(2)
                            .toNanos()
            );

            tracker.register(
                    peer,
                    second
            );

            now.set(
                    Duration.ofMinutes(9)
                            .toNanos()
            );

            tracker.remove(
                    peer,
                    first
            );

            /*
             * Completing the first queued block resets the
             * peer's download clock for the new queue head.
             */
            now.set(
                    Duration.ofMinutes(18)
                            .toNanos()
            );

            BlockDownloadTimeoutEvaluator.Evaluation result =
                    evaluator(tracker)
                            .evaluate(
                                    peer
                            );

            assertEquals(
                    Duration.ofMinutes(9),
                    result.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(10),
                    result.timeout()
            );

            assertFalse(
                    result.timedOut()
            );

            /*
             * The individual second request is much older,
             * proving that the evaluator uses downloadingSince,
             * not requestedAt(second).
             */
            assertEquals(
                    Duration.ofMinutes(16)
                            .toNanos(),
                    tracker.ageNanos(
                            peer,
                            second
                    )
            );
        }
    }

    @Test
    void shouldPreserveEvaluationSnapshotWhenTrackerChanges()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        0L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockInFlightTracker tracker =
                    tracker(
                            now
                    );

            Hash256 firstBlock =
                    hash(
                            1
                    );

            Hash256 secondBlock =
                    hash(
                            2
                    );

            tracker.register(
                    firstPeer,
                    firstBlock
            );

            tracker.register(
                    secondPeer,
                    secondBlock
            );

            /*
             * Both peers have been downloading for 12 minutes.
             *
             * With one OTHER downloading peer:
             *
             * timeout =
             * 10 min * (1 + 0.5)
             * = 15 min
             *
             * Therefore neither peer is timed out in the
             * original tracker state.
             */
            now.set(
                    Duration.ofMinutes(
                            12
                    ).toNanos()
            );

            BlockDownloadTimeoutEvaluator evaluator =
                    evaluator(
                            tracker
                    );

            /*
             * This is the snapshot phase used by
             * BlockDownloadScheduler:
             *
             * evaluate every peer BEFORE mutating tracker state.
             */
            BlockDownloadTimeoutEvaluator.Evaluation firstSnapshot =
                    evaluator.evaluate(
                            firstPeer
                    );

            BlockDownloadTimeoutEvaluator.Evaluation secondSnapshot =
                    evaluator.evaluate(
                            secondPeer
                    );

            assertEquals(
                    Duration.ofMinutes(
                            15
                    ),
                    firstSnapshot.timeout()
            );

            assertEquals(
                    Duration.ofMinutes(
                            15
                    ),
                    secondSnapshot.timeout()
            );

            assertEquals(
                    Duration.ofMinutes(
                            12
                    ),
                    firstSnapshot.downloadingAge()
            );

            assertEquals(
                    Duration.ofMinutes(
                            12
                    ),
                    secondSnapshot.downloadingAge()
            );

            assertFalse(
                    firstSnapshot.timedOut()
            );

            assertFalse(
                    secondSnapshot.timedOut()
            );

            /*
             * Simulate peer-wide cleanup AFTER the evaluations
             * have been captured.
             *
             * This mutates the tracker, but it must not alter
             * the already captured decision for secondPeer.
             */
            tracker.removeAll(
                    firstPeer
            );

            assertEquals(
                    1,
                    tracker.downloadingPeerCount()
            );

            assertFalse(
                    secondSnapshot.timedOut()
            );

            assertEquals(
                    Duration.ofMinutes(
                            15
                    ),
                    secondSnapshot.timeout()
            );

            /*
             * A NEW evaluation observes the changed tracker.
             *
             * secondPeer now has zero OTHER downloading peers:
             *
             * timeout = 10 min
             *
             * age = 12 min
             *
             * so a fresh evaluation is timed out.
             *
             * This proves why scheduler evaluation and cleanup
             * must be separate phases.
             */
            BlockDownloadTimeoutEvaluator.Evaluation afterMutation =
                    evaluator.evaluate(
                            secondPeer
                    );

            assertEquals(
                    Duration.ofMinutes(
                            10
                    ),
                    afterMutation.timeout()
            );

            assertEquals(
                    Duration.ofMinutes(
                            12
                    ),
                    afterMutation.downloadingAge()
            );

            assertTrue(
                    afterMutation.timedOut()
            );
        }
    }

    private static BlockInFlightTracker tracker(
            AtomicLong now
    ) {

        return new BlockInFlightTracker(
                16,
                now::get
        );
    }

    private static BlockDownloadTimeoutEvaluator evaluator(
            BlockInFlightTracker tracker
    ) {

        return new BlockDownloadTimeoutEvaluator(
                tracker,
                new BlockDownloadTimeoutPolicy(
                        Duration.ofMinutes(10)
                )
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

    private static Hash256 hash(
            int value
    ) {

        byte[] bytes =
                new byte[32];

        bytes[28] =
                (byte) (value >>> 24);

        bytes[29] =
                (byte) (value >>> 16);

        bytes[30] =
                (byte) (value >>> 8);

        bytes[31] =
                (byte) value;

        return new Hash256(
                bytes
        );
    }
}