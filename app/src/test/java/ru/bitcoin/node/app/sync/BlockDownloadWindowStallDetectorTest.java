package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexFactory;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadWindowStallDetectorTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    private final BlockDownloadWindowStallDetector detector =
            new BlockDownloadWindowStallDetector();

    @Test
    void shouldNotReportStallWhenMissingWorkInsideWindowIsStillAssignable()
            throws Exception {

        List<BlockIndex> path =
                path(3);

        try (Peer peer =
                     peer()) {

            Map<BlockIndex, Peer> owners =
                    new HashMap<>();

            /*
             * B1 is already in-flight, but B2 is still missing
             * and unassigned inside the same window.
             *
             * Therefore the window itself is not blocking
             * additional useful work.
             */
            owners.put(
                    path.get(0),
                    peer
            );

            Optional<Peer> stallingPeer =
                    detector.findStallingPeer(
                            path,
                            0,
                            2,
                            index -> false,
                            index -> Optional.ofNullable(
                                    owners.get(index)
                            )
                    );

            assertTrue(
                    stallingPeer.isEmpty()
            );
        }
    }

    @Test
    void shouldNotReportStallWhenWindowReachesEndOfPath()
            throws Exception {

        List<BlockIndex> path =
                path(2);

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            Map<BlockIndex, Peer> owners =
                    Map.of(
                            path.get(0),
                            firstPeer,
                            path.get(1),
                            secondPeer
                    );

            /*
             * Both positions are in-flight, but there is no B3
             * beyond the window.
             *
             * Nothing is being prevented from entering the
             * download window, so this is not a window stall.
             */
            Optional<Peer> stallingPeer =
                    detector.findStallingPeer(
                            path,
                            0,
                            2,
                            index -> false,
                            index -> Optional.ofNullable(
                                    owners.get(index)
                            )
                    );

            assertTrue(
                    stallingPeer.isEmpty()
            );
        }
    }

    @Test
    void shouldReportOwnerOfFirstBlockingInFlightBlock()
            throws Exception {

        List<BlockIndex> path =
                path(3);

        try (Peer firstPeer =
                     peer();

             Peer secondPeer =
                     peer()) {

            Map<BlockIndex, Peer> owners =
                    Map.of(
                            path.get(0),
                            firstPeer,
                            path.get(1),
                            secondPeer
                    );

            /*
             * Window:
             *
             *   B1  B2 | B3
             *   --------
             *
             * B1 and B2 are both already in-flight.
             * B3 exists beyond the window but cannot enter it
             * until the processing frontier advances.
             *
             * The first missing in-flight block is therefore
             * the blocker.
             */
            Optional<Peer> stallingPeer =
                    detector.findStallingPeer(
                            path,
                            0,
                            2,
                            index -> false,
                            index -> Optional.ofNullable(
                                    owners.get(index)
                            )
                    );

            assertTrue(
                    stallingPeer.isPresent()
            );

            assertSame(
                    firstPeer,
                    stallingPeer.orElseThrow()
            );
        }
    }

    @Test
    void shouldTreatBufferedCompletedBlockAsAvailableInsideWindow()
            throws Exception {

        List<BlockIndex> path =
                path(3);

        try (Peer firstPeer =
                     peer()) {

            Map<BlockIndex, Peer> owners =
                    Map.of(
                            path.get(0),
                            firstPeer
                    );

            Set<BlockIndex> available =
                    Set.of(
                            path.get(1)
                    );

            /*
             * Window:
             *
             *   B1  B2 | B3
             *
             * B1 = in-flight
             * B2 = already downloaded/buffered
             * B3 = beyond current window
             *
             * B2 must not be treated as an assignable hole.
             * It already consumes its position in the window.
             *
             * Therefore B1 is the block preventing the frontier
             * from advancing and admitting B3.
             */
            Optional<Peer> stallingPeer =
                    detector.findStallingPeer(
                            path,
                            0,
                            2,
                            available::contains,
                            index -> Optional.ofNullable(
                                    owners.get(index)
                            )
                    );

            assertTrue(
                    stallingPeer.isPresent()
            );

            assertSame(
                    firstPeer,
                    stallingPeer.orElseThrow()
            );
        }
    }

    private static List<BlockIndex> path(
            int count
    ) {

        BlockIndex parent =
                BlockIndexFactory.createGenesis(
                        GenesisBlockFactory.create(
                                PARAMETERS
                        ).header()
                );

        java.util.ArrayList<BlockIndex> indexes =
                new java.util.ArrayList<>(
                        count
                );

        for (int i = 0;
             i < count;
             i++) {

            BlockHeader parentHeader =
                    parent.header();

            BlockHeader header =
                    new BlockHeader(
                            parentHeader.version(),
                            parent.hash(),
                            parentHeader.merkleRoot(),
                            new UInt32(
                                    parentHeader.timestamp()
                                            .value() + 1
                            ),
                            parentHeader.bits(),
                            new UInt32(
                                    i + 1L
                            )
                    );

            parent =
                    BlockIndexFactory.createChild(
                            parent,
                            header
                    );

            indexes.add(
                    parent
            );
        }

        return List.copyOf(
                indexes
        );
    }

    private static Peer peer() {

        return new Peer(
                new PeerConnection(
                        PARAMETERS,
                        5_000,
                        5_000
                ),
                VersionMessage.DEFAULT_SERVICES,
                0,
                true
        );
    }
}