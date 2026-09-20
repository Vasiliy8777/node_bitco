package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BlockInFlightTrackerTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldTrackBlocksForPeer()
            throws Exception {

        try (Peer peer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            Hash256 first =
                    hash(
                            1
                    );

            Hash256 second =
                    hash(
                            2
                    );

            tracker.register(
                    peer,
                    first
            );

            tracker.register(
                    peer,
                    second
            );

            assertEquals(
                    2,
                    tracker.count(
                            peer
                    )
            );

            assertTrue(
                    tracker.contains(
                            first
                    )
            );

            assertTrue(
                    tracker.contains(
                            peer,
                            first
                    )
            );

            /*
             * Reverse index must point from an in-flight
             * block to the concrete Peer session that owns it.
             */
            assertSame(
                    peer,
                    tracker.peerForBlock(
                            first
                    )
            );

            assertSame(
                    peer,
                    tracker.peerForBlock(
                            second
                    )
            );

            assertEquals(
                    List.of(
                            first,
                            second
                    ),
                    new ArrayList<>(
                            tracker.blocks(
                                    peer
                            )
                    )
            );

            tracker.remove(
                    peer,
                    first
            );

            assertEquals(
                    1,
                    tracker.count(
                            peer
                    )
            );

            assertFalse(
                    tracker.contains(
                            first
                    )
            );

            /*
             * Removing the block must also remove
             * the reverse block -> peer mapping.
             */
            assertNull(
                    tracker.peerForBlock(
                            first
                    )
            );

            /*
             * The second in-flight block is still owned
             * by this peer.
             */
            assertTrue(
                    tracker.contains(
                            second
                    )
            );

            assertSame(
                    peer,
                    tracker.peerForBlock(
                            second
                    )
            );

            tracker.remove(
                    peer,
                    second
            );

            assertEquals(
                    0,
                    tracker.count(
                            peer
                    )
            );

            assertTrue(
                    tracker.blocks(
                            peer
                    ).isEmpty()
            );

            assertNull(
                    tracker.peerForBlock(
                            second
                    )
            );
        }
    }

    @Test
    void shouldEnforceSixteenBlockLimitPerPeer()
            throws Exception {

        try (Peer peer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            List<Hash256> hashes =
                    new ArrayList<>();

            for (int i = 0;
                 i < BlockInFlightTracker
                         .DEFAULT_MAX_BLOCKS_PER_PEER;
                 i++) {

                Hash256 blockHash =
                        hash(
                                i + 1
                        );

                hashes.add(
                        blockHash
                );

                assertTrue(
                        tracker.canRegister(
                                peer
                        )
                );

                tracker.register(
                        peer,
                        blockHash
                );

                assertSame(
                        peer,
                        tracker.peerForBlock(
                                blockHash
                        )
                );
            }

            assertEquals(
                    16,
                    tracker.count(
                            peer
                    )
            );

            assertFalse(
                    tracker.canRegister(
                            peer
                    )
            );

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> tracker.register(
                                    peer,
                                    hash(
                                            17
                                    )
                            )
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    "maximum"
                            )
            );

            /*
             * Removing one request must immediately
             * restore one unit of peer capacity.
             */
            tracker.remove(
                    peer,
                    hashes.get(0)
            );

            assertEquals(
                    15,
                    tracker.count(
                            peer
                    )
            );

            assertTrue(
                    tracker.canRegister(
                            peer
                    )
            );

            tracker.register(
                    peer,
                    hash(
                            17
                    )
            );

            assertEquals(
                    16,
                    tracker.count(
                            peer
                    )
            );
        }
    }

    @Test
    void shouldRejectSameBlockForDifferentPeers()
            throws Exception {

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            Hash256 blockHash =
                    hash(
                            100
                    );

            tracker.register(
                    firstPeer,
                    blockHash
            );

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> tracker.register(
                                    secondPeer,
                                    blockHash
                            )
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    "already in flight"
                            )
            );

            assertEquals(
                    1,
                    tracker.count(
                            firstPeer
                    )
            );

            assertEquals(
                    0,
                    tracker.count(
                            secondPeer
                    )
            );

            assertTrue(
                    tracker.contains(
                            firstPeer,
                            blockHash
                    )
            );
        }
    }

    @Test
    void shouldRejectRemovalFromWrongPeer()
            throws Exception {

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            Hash256 blockHash =
                    hash(
                            200
                    );

            tracker.register(
                    firstPeer,
                    blockHash
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> tracker.remove(
                            secondPeer,
                            blockHash
                    )
            );

            /*
             * Failed removal must not corrupt either index.
             */
            assertEquals(
                    1,
                    tracker.count(
                            firstPeer
                    )
            );

            assertTrue(
                    tracker.contains(
                            firstPeer,
                            blockHash
                    )
            );
        }
    }

    @Test
    void shouldUsePeerIdentity()
            throws Exception {

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            1
                    );

            tracker.register(
                    firstPeer,
                    hash(
                            1
                    )
            );

            /*
             * Capacity belongs to the concrete Peer session,
             * not globally to all peers.
             */
            assertFalse(
                    tracker.canRegister(
                            firstPeer
                    )
            );

            assertTrue(
                    tracker.canRegister(
                            secondPeer
                    )
            );

            tracker.register(
                    secondPeer,
                    hash(
                            2
                    )
            );

            assertEquals(
                    1,
                    tracker.count(
                            firstPeer
                    )
            );

            assertEquals(
                    1,
                    tracker.count(
                            secondPeer
                    )
            );
        }
    }

    @Test
    void shouldTrackRequestAgeWithoutSleeping()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            Hash256 first =
                    hash(
                            1
                    );

            tracker.register(
                    peer,
                    first
            );

            assertEquals(
                    1_000L,
                    tracker.requestedAtNanos(
                            peer,
                            first
                    )
            );

            assertEquals(
                    0L,
                    tracker.ageNanos(
                            peer,
                            first
                    )
            );

            now.set(
                    1_250L
            );

            assertEquals(
                    250L,
                    tracker.ageNanos(
                            peer,
                            first
                    )
            );

            Hash256 second =
                    hash(
                            2
                    );

            tracker.register(
                    peer,
                    second
            );

            assertEquals(
                    1_250L,
                    tracker.requestedAtNanos(
                            peer,
                            second
                    )
            );

            now.set(
                    1_500L
            );

            assertEquals(
                    500L,
                    tracker.ageNanos(
                            peer,
                            first
                    )
            );

            assertEquals(
                    250L,
                    tracker.ageNanos(
                            peer,
                            second
                    )
            );

            tracker.remove(
                    peer,
                    first
            );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tracker.requestedAtNanos(
                                    peer,
                                    first
                            )
            );
        }
    }

    @Test
    void shouldReturnOldestRequestForPeer()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            Hash256 first =
                    hash(
                            1
                    );

            Hash256 second =
                    hash(
                            2
                    );

            Hash256 third =
                    hash(
                            3
                    );

            tracker.register(
                    peer,
                    first
            );

            now.set(
                    1_100L
            );

            tracker.register(
                    peer,
                    second
            );

            now.set(
                    1_200L
            );

            tracker.register(
                    peer,
                    third
            );

            now.set(
                    1_500L
            );

            BlockInFlightTracker.OldestRequest oldest =
                    tracker.oldestRequest(
                            peer
                    );

            assertNotNull(
                    oldest
            );

            assertEquals(
                    first,
                    oldest.blockHash()
            );

            assertEquals(
                    1_000L,
                    oldest.requestedAtNanos()
            );

            assertEquals(
                    500L,
                    oldest.ageNanos()
            );

            /*
             * Once the oldest request completes,
             * the next registered request becomes oldest.
             */
            tracker.remove(
                    peer,
                    first
            );

            BlockInFlightTracker.OldestRequest next =
                    tracker.oldestRequest(
                            peer
                    );

            assertNotNull(
                    next
            );

            assertEquals(
                    second,
                    next.blockHash()
            );

            assertEquals(
                    1_100L,
                    next.requestedAtNanos()
            );

            assertEquals(
                    400L,
                    next.ageNanos()
            );
        }
    }

    @Test
    void shouldKeepOldestRequestsIndependentBetweenPeers()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        10_000L
                );

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            Hash256 firstHash =
                    hash(
                            10
                    );

            Hash256 secondHash =
                    hash(
                            20
                    );

            tracker.register(
                    firstPeer,
                    firstHash
            );

            now.set(
                    10_100L
            );

            tracker.register(
                    secondPeer,
                    secondHash
            );

            now.set(
                    10_500L
            );

            BlockInFlightTracker.OldestRequest firstOldest =
                    tracker.oldestRequest(
                            firstPeer
                    );

            BlockInFlightTracker.OldestRequest secondOldest =
                    tracker.oldestRequest(
                            secondPeer
                    );

            assertNotNull(
                    firstOldest
            );

            assertNotNull(
                    secondOldest
            );

            assertEquals(
                    firstHash,
                    firstOldest.blockHash()
            );

            assertEquals(
                    500L,
                    firstOldest.ageNanos()
            );

            assertEquals(
                    secondHash,
                    secondOldest.blockHash()
            );

            assertEquals(
                    400L,
                    secondOldest.ageNanos()
            );

            tracker.remove(
                    firstPeer,
                    firstHash
            );

            assertNull(
                    tracker.oldestRequest(
                            firstPeer
                    )
            );

            /*
             * Removing state for one peer must not
             * affect another peer.
             */
            BlockInFlightTracker.OldestRequest remaining =
                    tracker.oldestRequest(
                            secondPeer
                    );

            assertNotNull(
                    remaining
            );

            assertEquals(
                    secondHash,
                    remaining.blockHash()
            );

            assertEquals(
                    400L,
                    remaining.ageNanos()
            );
        }
    }

    @Test
    void shouldAdvanceDownloadingSinceWhenFirstBlockCompletes()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer peer =
                     peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            Hash256 first =
                    hash(
                            1
                    );

            Hash256 second =
                    hash(
                            2
                    );

            tracker.register(
                    peer,
                    first
            );

            now.set(
                    1_100L
            );

            tracker.register(
                    peer,
                    second
            );

            /*
             * The peer download timer starts when its
             * in-flight queue changes from empty to non-empty.
             */
            assertEquals(
                    1_000L,
                    tracker.downloadingSinceNanos(
                            peer
                    )
            );

            now.set(
                    1_500L
            );

            assertEquals(
                    500L,
                    tracker.downloadingAgeNanos(
                            peer
                    )
            );

            /*
             * Completing the first queued block advances
             * the download timer to now.
             */
            tracker.remove(
                    peer,
                    first
            );

            assertEquals(
                    1_500L,
                    tracker.downloadingSinceNanos(
                            peer
                    )
            );

            assertEquals(
                    0L,
                    tracker.downloadingAgeNanos(
                            peer
                    )
            );

            /*
             * Notice that second's original request timestamp
             * remains unchanged. These are deliberately two
             * different concepts.
             */
            assertEquals(
                    1_100L,
                    tracker.requestedAtNanos(
                            peer,
                            second
                    )
            );

            now.set(
                    1_800L
            );

            assertEquals(
                    700L,
                    tracker.ageNanos(
                            peer,
                            second
                    )
            );

            assertEquals(
                    300L,
                    tracker.downloadingAgeNanos(
                            peer
                    )
            );

            tracker.remove(
                    peer,
                    second
            );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tracker.downloadingSinceNanos(
                                    peer
                            )
            );
        }
    }

    @Test
    void shouldCountDownloadingPeersByIdentity()
            throws Exception {

        AtomicLong now =
                new AtomicLong();

        try (Peer firstPeer = peer();
             Peer secondPeer = peer();
             Peer thirdPeer = peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            assertEquals(
                    0,
                    tracker.downloadingPeerCount()
            );

            assertEquals(
                    0,
                    tracker.otherDownloadingPeerCount(
                            firstPeer
                    )
            );

            Hash256 firstBlock =
                    hash(1);

            Hash256 secondBlock =
                    hash(2);

            Hash256 thirdBlock =
                    hash(3);

            tracker.register(
                    firstPeer,
                    firstBlock
            );

            /*
             * Multiple blocks for one peer still count
             * as one downloading peer.
             */
            tracker.register(
                    firstPeer,
                    secondBlock
            );

            assertEquals(
                    1,
                    tracker.downloadingPeerCount()
            );

            assertEquals(
                    0,
                    tracker.otherDownloadingPeerCount(
                            firstPeer
                    )
            );

            /*
             * For a peer that is not itself downloading,
             * all currently downloading peers are "other".
             */
            assertEquals(
                    1,
                    tracker.otherDownloadingPeerCount(
                            secondPeer
                    )
            );

            tracker.register(
                    secondPeer,
                    thirdBlock
            );

            assertEquals(
                    2,
                    tracker.downloadingPeerCount()
            );

            assertEquals(
                    1,
                    tracker.otherDownloadingPeerCount(
                            firstPeer
                    )
            );

            assertEquals(
                    1,
                    tracker.otherDownloadingPeerCount(
                            secondPeer
                    )
            );

            assertEquals(
                    2,
                    tracker.otherDownloadingPeerCount(
                            thirdPeer
                    )
            );

            /*
             * Removing only one of firstPeer's two blocks
             * must not remove that peer from the count.
             */
            tracker.remove(
                    firstPeer,
                    firstBlock
            );

            assertEquals(
                    2,
                    tracker.downloadingPeerCount()
            );

            tracker.remove(
                    firstPeer,
                    secondBlock
            );

            assertEquals(
                    1,
                    tracker.downloadingPeerCount()
            );

            assertEquals(
                    0,
                    tracker.otherDownloadingPeerCount(
                            secondPeer
                    )
            );

            tracker.remove(
                    secondPeer,
                    thirdBlock
            );

            assertEquals(
                    0,
                    tracker.downloadingPeerCount()
            );
        }
    }

    @Test
    void shouldRemoveAllBlocksForOnePeerWithoutAffectingOthers()
            throws Exception {

        AtomicLong now =
                new AtomicLong(
                        1_000L
                );

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker(
                            16,
                            now::get
                    );

            Hash256 first =
                    hash(1);

            Hash256 second =
                    hash(2);

            Hash256 third =
                    hash(3);

            tracker.register(
                    firstPeer,
                    first
            );

            now.set(
                    1_100L
            );

            tracker.register(
                    firstPeer,
                    second
            );

            tracker.register(
                    secondPeer,
                    third
            );

            /*
             * Verify the reverse index before cleanup.
             */
            assertSame(
                    firstPeer,
                    tracker.peerForBlock(
                            first
                    )
            );

            assertSame(
                    firstPeer,
                    tracker.peerForBlock(
                            second
                    )
            );

            assertSame(
                    secondPeer,
                    tracker.peerForBlock(
                            third
                    )
            );

            assertEquals(
                    3,
                    tracker.count(firstPeer)
                            + tracker.count(secondPeer)
            );

            assertEquals(
                    2,
                    tracker.downloadingPeerCount()
            );

            Set<Hash256> removed =
                    tracker.removeAll(
                            firstPeer
                    );

            assertEquals(
                    new LinkedHashSet<>(
                            List.of(
                                    first,
                                    second
                            )
                    ),
                    removed
            );

            assertEquals(
                    0,
                    tracker.count(
                            firstPeer
                    )
            );

            assertFalse(
                    tracker.contains(
                            first
                    )
            );

            assertFalse(
                    tracker.contains(
                            second
                    )
            );

            /*
             * removeAll(firstPeer) must also remove
             * both reverse-index entries belonging
             * to firstPeer.
             */
            assertNull(
                    tracker.peerForBlock(
                            first
                    )
            );

            assertNull(
                    tracker.peerForBlock(
                            second
                    )
            );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tracker.downloadingSinceNanos(
                                    firstPeer
                            )
            );

            /*
             * secondPeer must remain completely untouched.
             */
            assertEquals(
                    1,
                    tracker.count(
                            secondPeer
                    )
            );

            assertTrue(
                    tracker.contains(
                            secondPeer,
                            third
                    )
            );

            /*
             * Its reverse-index entry must also remain.
             */
            assertSame(
                    secondPeer,
                    tracker.peerForBlock(
                            third
                    )
            );

            assertEquals(
                    1,
                    tracker.downloadingPeerCount()
            );

            assertEquals(
                    0,
                    tracker.otherDownloadingPeerCount(
                            secondPeer
                    )
            );
        }
    }

    @Test
    void shouldReturnEmptySetWhenPeerHasNoBlocks()
            throws Exception {

        try (Peer peer = peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            assertEquals(
                    Set.of(),
                    tracker.removeAll(
                            peer
                    )
            );

            assertEquals(
                    0,
                    tracker.downloadingPeerCount()
            );
        }
    }

    @Test
    void shouldAllowRemovedBlocksToBeAssignedAgain()
            throws Exception {

        try (Peer firstPeer = peer();
             Peer secondPeer = peer()) {

            BlockInFlightTracker tracker =
                    new BlockInFlightTracker();

            Hash256 blockHash =
                    hash(1);

            tracker.register(
                    firstPeer,
                    blockHash
            );

            tracker.removeAll(
                    firstPeer
            );

            tracker.register(
                    secondPeer,
                    blockHash
            );

            assertTrue(
                    tracker.contains(
                            secondPeer,
                            blockHash
                    )
            );

            assertEquals(
                    1,
                    tracker.count(
                            secondPeer
                    )
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