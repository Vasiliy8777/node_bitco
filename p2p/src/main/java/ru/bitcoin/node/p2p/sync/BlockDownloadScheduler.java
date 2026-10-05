package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockDownloadScheduler {

    static final Duration COMPLETION_CHECK_INTERVAL =
            Duration.ofMillis(
                    250
            );

    /** Default for callers that do not supply a pipeline limit. */
    public static final int MAX_BLOCKS_IN_FLIGHT_PER_PEER =
            BlockInFlightTracker
                    .DEFAULT_MAX_BLOCKS_PER_PEER;

    /** Shared per-session ceiling, equivalent to eight default 32-block pipelines. */
    public static final int MAX_TOTAL_BLOCKS_IN_FLIGHT = 256;
    private final int maxBlocksInFlightPerPeer;

    private final PeerManager peerManager;

    private final BlockDownloadService blockDownloadService;

    private final BlockDownloadTimeoutPolicy timeoutPolicy;

    /*
     * Active sessions are registered so alternative block transports
     * (currently BIP152) can satisfy the same logical download instead of
     * creating a second, independent block-download lifecycle.
     */
    private final Set<BlockDownloadSession> activeSessions =
            ConcurrentHashMap.newKeySet();

    public BlockDownloadScheduler(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {

        this(peerManager, blockDownloadService, timeoutPolicy, MAX_BLOCKS_IN_FLIGHT_PER_PEER);
    }

    /** Configurable pipeline depth, bounded by the shared session request budget. */
    public BlockDownloadScheduler(PeerManager peerManager, BlockDownloadService blockDownloadService,
                                  BlockDownloadTimeoutPolicy timeoutPolicy, int maxBlocksInFlightPerPeer) {
        if (maxBlocksInFlightPerPeer < 1 || maxBlocksInFlightPerPeer > MAX_TOTAL_BLOCKS_IN_FLIGHT)
            throw new IllegalArgumentException("maxBlocksInFlightPerPeer must be between 1 and " + MAX_TOTAL_BLOCKS_IN_FLIGHT);
        this.maxBlocksInFlightPerPeer = maxBlocksInFlightPerPeer;
        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.blockDownloadService =
                Objects.requireNonNull(
                        blockDownloadService,
                        "blockDownloadService"
                );

        this.timeoutPolicy =
                Objects.requireNonNull(
                        timeoutPolicy,
                        "timeoutPolicy"
                );
    }

    public int maxBlocksInFlightPerPeer() { return maxBlocksInFlightPerPeer; }

    public BlockDownloadSession openSession() {

        BlockDownloadSession session;
        if (maxBlocksInFlightPerPeer >= ReplicatedFrontierBlockDownloadSession.CACHE_BLOCKS_PER_PEER) {
            session = new ReplicatedFrontierBlockDownloadSession(
                    peerManager,
                    blockDownloadService,
                    timeoutPolicy,
                    this::sessionClosed
            );
        } else {
            session = new SchedulerBlockDownloadSession(
                    peerManager,
                    blockDownloadService,
                    timeoutPolicy,
                    ignored -> sessionClosed(ignored),
                    maxBlocksInFlightPerPeer
            );
        }

        activeSessions.add(
                session
        );

        return session;
    }

    /**
     * Offers a block obtained outside the ordinary GETDATA/BLOCK path to the
     * currently active download sessions. Returns true when a pending
     * scheduled download accepted the block.
     */
    public boolean acceptBlock(
            ru.bitcoin.node.p2p.Peer sourcePeer,
            Block block
    ) {
        Objects.requireNonNull(sourcePeer, "sourcePeer");
        Objects.requireNonNull(block, "block");

        for (BlockDownloadSession session : activeSessions) {
            if (session instanceof SchedulerBlockDownloadSession scheduler
                    && scheduler.acceptExternalBlock(sourcePeer, block)) return true;
            if (session instanceof ReplicatedFrontierBlockDownloadSession replicated
                    && replicated.acceptExternalBlock(sourcePeer, block)) return true;
        }

        return false;
    }

    /**
     * Returns true when an active scheduler session already owns the block.
     * Alternative transports use this to avoid starting a second network
     * round-trip for a hash that is already being downloaded normally.
     */
    public boolean hasPendingBlock(
            Hash256 blockHash
    ) {
        Objects.requireNonNull(blockHash, "blockHash");

        for (BlockDownloadSession session : activeSessions) {
            if (session instanceof SchedulerBlockDownloadSession scheduler
                    && scheduler.hasPendingBlock(blockHash)) return true;
            if (session instanceof ReplicatedFrontierBlockDownloadSession replicated
                    && replicated.hasPendingBlock(blockHash)) return true;
        }

        return false;
    }

    /**
     * Returns true while an active scheduler session owns the submitted hash,
     * even when an alternative transport has already completed that logical
     * download and the completion is waiting to be consumed.
     */
    public boolean hasSubmittedBlock(
            Hash256 blockHash
    ) {
        Objects.requireNonNull(blockHash, "blockHash");

        for (BlockDownloadSession session : activeSessions) {
            if (session instanceof SchedulerBlockDownloadSession scheduler
                    && scheduler.hasSubmittedBlock(blockHash)) return true;
            if (session instanceof ReplicatedFrontierBlockDownloadSession replicated
                    && replicated.hasSubmittedBlock(blockHash)) return true;
        }

        return false;
    }

    private void sessionClosed(
            BlockDownloadSession session
    ) {
        activeSessions.remove(
                session
        );
    }

    public List<Block> download(
            List<Hash256> blockHashes
    ) throws IOException {

        Objects.requireNonNull(
                blockHashes,
                "blockHashes"
        );

        if (blockHashes.isEmpty()) {
            return List.of();
        }

        /*
         * Preserve the scheduler's public contract:
         * results are returned in the same order as blockHashes,
         * even though the session reports blocks in completion order.
         */
        Block[] results =
                new Block[
                        blockHashes.size()
                        ];

        try (BlockDownloadSession session =
                     openSession()) {

            session.submit(
                    blockHashes
            );

            while (session.pendingCount() > 0) {

                CompletedBlockDownload completed =
                        session.awaitCompleted();

                int index =
                        completed.index();

                if (index < 0
                        || index >= results.length) {

                    throw new IOException(
                            "Completed block download index is outside submitted batch: "
                                    + index
                    );
                }

                if (results[index] != null) {

                    throw new IOException(
                            "Block download completed more than once at index "
                                    + index
                    );
                }

                Hash256 expectedHash =
                        blockHashes.get(
                                index
                        );

                if (!expectedHash.equals(
                        completed.requestedHash()
                )) {

                    throw new IOException(
                            "Completed block download does not match submitted index "
                                    + index
                                    + ": expected "
                                    + expectedHash.toDisplayHex()
                                    + ", actual "
                                    + completed.requestedHash()
                                    .toDisplayHex()
                    );
                }

                results[index] =
                        completed.block();
            }
        }

        List<Block> ordered =
                new ArrayList<>(
                        results.length
                );

        for (int i = 0;
             i < results.length;
             i++) {

            Block block =
                    results[i];

            if (block == null) {

                throw new IOException(
                        "Block download session completed without result for index "
                                + i
                );
            }

            ordered.add(
                    block
            );
        }

        return List.copyOf(
                ordered
        );
    }
}