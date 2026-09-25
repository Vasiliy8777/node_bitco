package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.chain.ReorganizationPlan;
import ru.bitcoin.node.chain.ReorganizationPlanner;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.block.BlockStore;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class BlockSyncCoordinator {

    private final Object lifecycleLock = new Object();
    private boolean cancelled;
    private BlockDownloadSession activeSession;
    /*
     * Bitcoin Core BLOCK_DOWNLOAD_WINDOW.
     *
     * This limits how far block-body download may advance
     * ahead of the currently processed chain tip.
     *
     * It is intentionally independent from the per-peer
     * in-flight request limit.
     */
    private static final int DEFAULT_DOWNLOAD_WINDOW = 1024;
    private static final Duration DOWNLOAD_COMPLETION_POLL_INTERVAL =
            Duration.ofMillis(250);
    private final int downloadWindow;
    private final BlockDownloadScheduler blockDownloadScheduler;
    private final NodeValidationService validationService;
    private final HeaderChainState headerChainState;
    private final BlockIndexLookup lookup;
    private final BlockStore blockStore;
    private final ConnectedBlockListener connectedBlockListener;

    private final BlockDownloadWindowStallDetector stallDetector;
    private final BlockDownloadStallTracker stallTracker;
    private final BlockDownloadStallTimeoutEvaluator stallTimeoutEvaluator;

    public BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore
    ) {
        this(
                blockDownloadScheduler,
                validationService,
                headerChainState,
                lookup,
                blockStore,
                DEFAULT_DOWNLOAD_WINDOW,
                ConnectedBlockListener.NOOP
        );
    }

    public BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow
    ) {
        this(
                blockDownloadScheduler,
                validationService,
                headerChainState,
                lookup,
                blockStore,
                downloadWindow,
                ConnectedBlockListener.NOOP,
                new BlockDownloadStallTracker()
        );
    }

    public BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow,
            ConnectedBlockListener connectedBlockListener
    ) {
        this(
                blockDownloadScheduler,
                validationService,
                headerChainState,
                lookup,
                blockStore,
                downloadWindow,
                connectedBlockListener,
                new BlockDownloadStallTracker()
        );
    }

    BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow,
            ConnectedBlockListener connectedBlockListener,
            BlockDownloadStallTracker stallTracker
    ) {
        this(
                blockDownloadScheduler,
                validationService,
                headerChainState,
                lookup,
                blockStore,
                downloadWindow,
                connectedBlockListener,
                stallTracker,
                new BlockDownloadStallTimeoutEvaluator(
                        stallTracker,
                        new BlockDownloadStallTimeoutPolicy()
                )
        );
    }

    BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow,
            BlockDownloadStallTracker stallTracker
    ) {
        this(
                blockDownloadScheduler,
                validationService,
                headerChainState,
                lookup,
                blockStore,
                downloadWindow,
                ConnectedBlockListener.NOOP,
                stallTracker,
                new BlockDownloadStallTimeoutEvaluator(
                        stallTracker,
                        new BlockDownloadStallTimeoutPolicy()
                )
        );
    }

    BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow,
            BlockDownloadStallTracker stallTracker,
            BlockDownloadStallTimeoutEvaluator stallTimeoutEvaluator
    ) {
        this(blockDownloadScheduler, validationService, headerChainState, lookup, blockStore,
                downloadWindow, ConnectedBlockListener.NOOP, stallTracker, stallTimeoutEvaluator);
    }

    BlockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            HeaderChainState headerChainState,
            BlockIndexLookup lookup,
            BlockStore blockStore,
            int downloadWindow,
            ConnectedBlockListener connectedBlockListener,
            BlockDownloadStallTracker stallTracker,
            BlockDownloadStallTimeoutEvaluator stallTimeoutEvaluator
    ) {
        if (downloadWindow <= 0) {
            throw new IllegalArgumentException(
                    "downloadWindow must be positive"
            );
        }

        this.blockDownloadScheduler =
                Objects.requireNonNull(
                        blockDownloadScheduler,
                        "blockDownloadScheduler"
                );

        this.validationService =
                Objects.requireNonNull(
                        validationService,
                        "validationService"
                );

        this.headerChainState =
                Objects.requireNonNull(
                        headerChainState,
                        "headerChainState"
                );

        this.lookup =
                Objects.requireNonNull(
                        lookup,
                        "lookup"
                );

        this.blockStore =
                Objects.requireNonNull(
                        blockStore,
                        "blockStore"
                );

        this.connectedBlockListener =
                Objects.requireNonNull(
                        connectedBlockListener,
                        "connectedBlockListener"
                );

        this.downloadWindow =
                downloadWindow;

        this.stallDetector =
                new BlockDownloadWindowStallDetector();

        this.stallTracker =
                Objects.requireNonNull(
                        stallTracker,
                        "stallTracker"
                );

        this.stallTimeoutEvaluator =
                Objects.requireNonNull(
                        stallTimeoutEvaluator,
                        "stallTimeoutEvaluator"
                );
    }

    public List<BlockIndex> synchronize()
            throws IOException {
        return synchronize(
                Integer.MAX_VALUE
        );
    }

    public List<BlockIndex> synchronize(
            int maxBlocks
    ) throws IOException {

        ensureNotCancelled();

        if (maxBlocks <= 0) {
            throw new IllegalArgumentException(
                    "maxBlocks must be positive"
            );
        }

        BlockIndex activeTip =
                validationService.activeTip();

        BlockIndex bestHeaderTip =
                headerChainState.bestHeaderTip();

        if (activeTip.hash().equals(
                bestHeaderTip.hash()
        )) {
            return List.of();
        }

        ReorganizationPlan plan =
                ReorganizationPlanner.plan(
                        activeTip,
                        bestHeaderTip,
                        lookup
                );

        List<BlockIndex> blocksToConnect =
                plan.blocksToConnect();

        int downloadCount =
                Math.min(
                        maxBlocks,
                        blocksToConnect.size()
                );

        List<BlockIndex> blocksToDownload =
                blocksToConnect.subList(
                        0,
                        downloadCount
                );

        /*
         * Bodies which are already available either from local
         * storage or from an asynchronous network completion.
         *
         * Consensus processing still consumes this map strictly
         * in blocksToDownload order.
         */
        Map<Hash256, AvailableBlock> availableBlocks =
                new HashMap<>();

        /*
         * nextToExpose:
         *     first connect-path position which has not yet entered
         *     the download horizon.
         *
         * nextToProcess:
         *     first connect-path position which has not yet passed
         *     through validationService.processBlock().
         *
         * The permitted horizon is:
         *
         * [nextToProcess, nextToProcess + downloadWindow)
         */
        int nextToExpose = 0;
        int nextToProcess = 0;

        BlockDownloadSession session = openActiveSession();

        try (session) {

            while (nextToProcess
                    < blocksToDownload.size()) {

                /*
                 * Extend the horizon as far as currently permitted.
                 *
                 * Local bodies enter availableBlocks immediately.
                 * Missing bodies are submitted to the SAME long-lived
                 * download session.
                 */
                int horizonEnd =
                        Math.min(
                                blocksToDownload.size(),
                                Math.addExact(
                                        nextToProcess,
                                        downloadWindow
                                )
                        );

                List<BlockDownloadRequest> missingToSubmit =
                        new ArrayList<>();

                while (nextToExpose
                        < horizonEnd) {

                    BlockIndex index =
                            blocksToDownload.get(
                                    nextToExpose
                            );

                    Block localBlock =
                            blockStore.find(
                                            index.hash()
                                    )
                                    .orElse(null);

                    if (localBlock != null) {

                        AvailableBlock previous =
                                availableBlocks.put(
                                        index.hash(),
                                        new AvailableBlock(localBlock, null)
                                );

                        if (previous != null) {
                            throw new IllegalStateException(
                                    "Block body became available more than once: "
                                            + index.hash()
                                            .toDisplayHex()
                            );
                        }

                    } else {

                        missingToSubmit.add(
                                new BlockDownloadRequest(
                                        index.hash(),
                                        index.height()
                                )
                        );
                    }

                    nextToExpose =
                            Math.incrementExact(
                                    nextToExpose
                            );
                }

                if (!missingToSubmit.isEmpty()) {

                    session.submitRequests(
                            missingToSubmit
                    );
                }

                /*
                 * Process every contiguous body which is already
                 * available.
                 *
                 * This is the operation which slides the horizon.
                 */
                boolean processedAny =
                        false;

                while (nextToProcess
                        < blocksToDownload.size()) {

                    BlockIndex index =
                            blocksToDownload.get(
                                    nextToProcess
                            );

                    AvailableBlock available =
                            availableBlocks.remove(
                                    index.hash()
                            );

                    if (available == null) {
                        break;
                    }

                    Block block = available.block();

                    if (!block.hash().equals(
                            index.hash()
                    )) {
                        throw new IllegalStateException(
                                "Available block does not match connect path: "
                                        + "expected "
                                        + index.hash()
                                        .toDisplayHex()
                                        + ", actual "
                                        + block.hash()
                                        .toDisplayHex()
                        );
                    }

                    BlockProcessingResult result =
                            validationService.processBlock(
                                    block
                            );

                    if (result ==
                            BlockProcessingResult.UNKNOWN_PARENT) {

                        throw new IllegalStateException(
                                "Block has unknown parent: "
                                        + index.hash()
                                        .toDisplayHex()
                                        + " at height "
                                        + index.height()
                        );
                    }

                    if (result == BlockProcessingResult.CONNECTED) {
                        connectedBlockListener.onConnected(block, available.sourcePeer());
                    }

                    nextToProcess =
                            Math.incrementExact(
                                    nextToProcess
                            );

                    processedAny =
                            true;
                }

                /*
                 * Processing one or more blocks changed the left
                 * edge of the horizon.
                 *
                 * Loop immediately so newly admitted positions are
                 * submitted BEFORE waiting for older outstanding
                 * downloads.
                 *
                 * Example with window=2:
                 *
                 * B1,B2 submitted
                 * B1 completes
                 * B1 processed
                 * loop
                 * B3 submitted while B2 remains in-flight
                 */
                if (processedAny) {

                    stallTracker.update(
                            null
                    );

                    continue;
                }

                if (session.pendingCount() == 0) {

                    stallTracker.update(
                            null
                    );

                    throw new IOException(
                            "No pending block downloads while synchronization is incomplete"
                    );
                }

                Optional<Peer> stallingPeer =
                        stallDetector.findStallingPeer(
                                blocksToDownload,
                                nextToProcess,
                                downloadWindow,
                                index -> {

                                    Hash256 hash =
                                            index.hash();

                                    if (availableBlocks.containsKey(
                                            hash
                                    )) {
                                        return true;
                                    }

                                    return blockStore.find(
                                            hash
                                    ).isPresent();
                                },
                                index ->
                                        session.inFlightPeer(
                                                index.hash()
                                        )
                        );

                stallTracker.update(
                        stallingPeer.orElse(
                                null
                        )
                );

                BlockDownloadStallTimeoutEvaluator.Evaluation
                        stallEvaluation =
                        stallTimeoutEvaluator.evaluate();

                if (stallEvaluation.timedOut()) {

                    Peer timedOutPeer =
                            stallEvaluation.peer();

                    IOException stallFailure =
                            new IOException(
                                    "Peer stalled block download window for "
                                            + stallEvaluation.stallingAge()
                                            + " with timeout "
                                            + stallEvaluation.timeout()
                            );

                    /*
                     * Release every block currently assigned to the
                     * stalling peer before disconnecting it.
                     *
                     * The session keeps those blocks pending so they
                     * can be reassigned to another ready peer.
                     */
                    session.failPeer(
                            timedOutPeer,
                            stallFailure
                    );

                    try {
                        timedOutPeer.close();
                    } catch (IOException closeException) {
                        stallFailure.addSuppressed(
                                closeException
                        );
                    }

                    /*
                     * A real stall timeout was handled, therefore
                     * increase the adaptive timeout for a subsequent
                     * stall event.
                     */
                    stallTimeoutEvaluator.timeoutHandled();

                    stallTracker.clear(
                            timedOutPeer
                    );

                    /*
                     * Re-enter the coordinator loop. The session will
                     * assign the released blocks to another ready peer.
                     */
                    continue;
                }

                Optional<CompletedBlockDownload> completedOptional =
                        session.pollCompleted(
                                DOWNLOAD_COMPLETION_POLL_INTERVAL
                        );

                if (completedOptional.isEmpty()) {
                    continue;
                }

                CompletedBlockDownload completed =
                        completedOptional.get();

                Hash256 completedHash =
                        completed.requestedHash();

                Block completedBlock =
                        completed.block();

                if (!completedHash.equals(
                        completedBlock.hash()
                )) {
                    throw new IllegalStateException(
                            "Completed block does not match requested hash: "
                                    + "expected "
                                    + completedHash.toDisplayHex()
                                    + ", actual "
                                    + completedBlock.hash()
                                    .toDisplayHex()
                    );
                }

                AvailableBlock previous =
                        availableBlocks.put(
                                completedHash,
                                new AvailableBlock(completedBlock, completed.sourcePeer())
                        );

                if (previous != null) {
                    throw new IllegalStateException(
                            "Block body completed more than once: "
                                    + completedHash.toDisplayHex()
                    );
                }
            }

            /*
             * Every network request admitted into the horizon must
             * have completed before the complete bounded path can
             * have been processed.
             */
            if (session.pendingCount() != 0) {
                throw new IllegalStateException(
                        "Block synchronization processed its bounded path "
                                + "with "
                                + session.pendingCount()
                                + " download(s) still pending"
                );
            }
        } finally {

            clearActiveSession(session);
            stallTracker.clear();
        }

        /*
         * Reaching bestHeaderTip is required only when this invocation
         * covered the complete remaining connect path.
         */
        boolean complete =
                downloadCount
                        == blocksToConnect.size();

        if (complete) {

            BlockIndex finalTip =
                    validationService.activeTip();

            // A concurrently submitted local block may already extend this download's target.
            while (finalTip.height() > bestHeaderTip.height()) {
                finalTip = Objects.requireNonNull(lookup.find(finalTip.previousBlockHash()), "Missing active ancestor");
            }
            if (!finalTip.hash().equals(
                    bestHeaderTip.hash()
            )) {

                throw new IllegalStateException(
                        "Block synchronization completed "
                                + "without activating best header tip: "
                                + "expected "
                                + bestHeaderTip.hash()
                                .toDisplayHex()
                                + ", actual "
                                + finalTip.hash()
                                .toDisplayHex()
                );
            }
        }

        return List.copyOf(
                blocksToDownload
        );
    }
    public void cancel() {

        BlockDownloadSession sessionToClose;

        synchronized (lifecycleLock) {

            cancelled = true;
            sessionToClose = activeSession;
        }

        if (sessionToClose != null) {
            sessionToClose.close();
        }
    }

    private void ensureNotCancelled()
            throws IOException {

        synchronized (lifecycleLock) {

            if (cancelled) {
                throw new IOException(
                        "Block synchronization cancelled"
                );
            }
        }
    }

    private BlockDownloadSession openActiveSession()
            throws IOException {

        synchronized (lifecycleLock) {

            if (cancelled) {
                throw new IOException(
                        "Block synchronization cancelled"
                );
            }

            BlockDownloadSession session =
                    blockDownloadScheduler.openSession();

            activeSession = session;
            return session;
        }
    }

    private void clearActiveSession(
            BlockDownloadSession session
    ) {

        synchronized (lifecycleLock) {

            if (activeSession == session) {
                activeSession = null;
            }
        }
    }

    private record AvailableBlock(Block block, Peer sourcePeer) {
        private AvailableBlock {
            Objects.requireNonNull(block, "block");
        }
    }

}
