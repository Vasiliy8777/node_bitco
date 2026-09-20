package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadStallTrackerTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldStartStallTimerForPeer()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            assertFalse(
                    tracker.isStalling()
            );

            tracker.update(
                    peer
            );

            assertTrue(
                    tracker.isStalling()
            );

            assertTrue(
                    tracker.isStalling(
                            peer
                    )
            );

            assertSame(
                    peer,
                    tracker.stallingPeer()
            );

            assertEquals(
                    1_000L,
                    tracker.stallingSinceNanos()
            );

            assertEquals(
                    0L,
                    tracker.stallingAgeNanos()
            );

            now.set(
                    1_500L
            );

            assertEquals(
                    500L,
                    tracker.stallingAgeNanos()
            );
        }
    }

    @Test
    void shouldNotRestartTimerWhileSamePeerKeepsStalling()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            tracker.update(
                    peer
            );

            now.set(
                    1_500L
            );

            tracker.update(
                    peer
            );

            assertEquals(
                    1_000L,
                    tracker.stallingSinceNanos()
            );

            assertEquals(
                    500L,
                    tracker.stallingAgeNanos()
            );
        }
    }

    @Test
    void shouldRestartTimerWhenDifferentPeerBecomesStalling()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            tracker.update(
                    firstPeer
            );

            now.set(
                    1_500L
            );

            tracker.update(
                    secondPeer
            );

            assertFalse(
                    tracker.isStalling(
                            firstPeer
                    )
            );

            assertTrue(
                    tracker.isStalling(
                            secondPeer
                    )
            );

            assertSame(
                    secondPeer,
                    tracker.stallingPeer()
            );

            assertEquals(
                    1_500L,
                    tracker.stallingSinceNanos()
            );

            now.set(
                    1_800L
            );

            assertEquals(
                    300L,
                    tracker.stallingAgeNanos()
            );
        }
    }

    @Test
    void shouldClearStallWhenThereIsNoBlockingPeer()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            tracker.update(
                    peer
            );

            assertTrue(
                    tracker.isStalling()
            );

            tracker.update(
                    null
            );

            assertFalse(
                    tracker.isStalling()
            );

            assertNull(
                    tracker.stallingPeer()
            );

            assertThrows(
                    IllegalStateException.class,
                    tracker::stallingSinceNanos
            );

            assertThrows(
                    IllegalStateException.class,
                    tracker::stallingAgeNanos
            );
        }
    }

    @Test
    void shouldClearOnlyMatchingPeer()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            tracker.update(
                    firstPeer
            );

            /*
             * Clearing another peer must not disturb
             * the current stall period.
             */
            tracker.clear(
                    secondPeer
            );

            assertTrue(
                    tracker.isStalling(
                            firstPeer
                    )
            );

            assertEquals(
                    1_000L,
                    tracker.stallingSinceNanos()
            );

            tracker.clear(
                    firstPeer
            );

            assertFalse(
                    tracker.isStalling()
            );

            assertNull(
                    tracker.stallingPeer()
            );
        }
    }

    @Test
    void shouldAllowNewStallAfterClear()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockDownloadStallTracker tracker =
                    new BlockDownloadStallTracker(
                            now::get
                    );

            tracker.update(
                    peer
            );

            now.set(
                    1_500L
            );

            tracker.clear();

            assertFalse(
                    tracker.isStalling()
            );

            now.set(
                    2_000L
            );

            tracker.update(
                    peer
            );

            assertTrue(
                    tracker.isStalling(
                            peer
                    )
            );

            assertEquals(
                    2_000L,
                    tracker.stallingSinceNanos()
            );

            assertEquals(
                    0L,
                    tracker.stallingAgeNanos()
            );
        }
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