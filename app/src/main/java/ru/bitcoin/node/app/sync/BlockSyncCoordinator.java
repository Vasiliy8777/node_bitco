package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.block.BlockStore;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class BlockSyncCoordinator {

    private static final System.Logger log =
            System.getLogger(BlockSyncCoordinator.class.getName());


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

    /*
     * Block-index materialization is deliberately larger than the logical
     * download horizon. If both sizes are identical, the materialized chunk
     * itself becomes a false sliding-window boundary: after processing the
     * first block of a chunk the scheduler cannot expose a block from the next
     * chunk even though that height is already inside the logical horizon.
     *
     * 8192 BlockIndex references/objects remain bounded for IBD while giving
     * the 1024-block download window ample look-ahead. Small test windows also
     * retain true sliding behaviour because short paths fit in one chunk.
     */
    private static final int BLOCK_INDEX_MATERIALIZATION_CHUNK = 8192;
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
        return synchronizeInternal(Integer.MAX_VALUE, true);
    }

    public List<BlockIndex> synchronize(
            int maxBlocks
    ) throws IOException {
        return synchronizeInternal(maxBlocks, true);
    }

    /**
     * Production IBD entry point.
     *
     * <p>Unlike {@link #synchronize()}, this method does not retain every
     * processed {@link BlockIndex} until the complete IBD finishes. A public
     * test/API caller can still use synchronize() when it needs the returned
     * path, while lifecycle synchronization keeps connect-path materialization
     * bounded independently of total chain height.</p>
     */
    public void synchronizeToTip()
            throws IOException {
        synchronizeInternal(Integer.MAX_VALUE, false);
    }

    private List<BlockIndex> synchronizeInternal(
            int maxBlocks,
            boolean collectResult
    ) throws IOException {

        ensureNotCancelled();

        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("maxBlocks must be positive");
        }

        BlockIndex activeTip = validationService.activeTip();
        BlockIndex bestHeaderTip = headerChainState.bestHeaderTip();

        if (activeTip.hash().equals(bestHeaderTip.hash())) {
            return List.of();
        }

        /*
         * Do NOT materialize ReorganizationPlanner.plan(...).blocksToConnect()
         * here. During first IBD that path can contain several million indexes.
         * Find the branch point once, then materialize only the current bounded
         * download window. StoredBlockIndexLookup uses Core-style skip pointers
         * for the height jump to each window end.
         */
        BlockIndex commonAncestor =
                ru.bitcoin.node.chain.CommonAncestorFinder.find(
                        activeTip,
                        bestHeaderTip,
                        lookup
                );

        long remainingLong =
                Math.subtractExact(bestHeaderTip.height(), commonAncestor.height());

        if (remainingLong <= 0) {
            throw new IllegalStateException(
                    "Best header tip does not extend the common ancestor: activeHeight="
                            + activeTip.height() + ", bestHeaderHeight=" + bestHeaderTip.height()
            );
        }

        int downloadCount = (int) Math.min((long) maxBlocks, remainingLong);
        List<BlockIndex> result = collectResult
                ? new ArrayList<>(Math.min(downloadCount, downloadWindow))
                : null;

        final long targetHeight = bestHeaderTip.height();
        SyncProgressConsole.blocks(
                activeTip.height(),
                targetHeight,
                activeTip.header().timestamp().value()
        );

        BlockDownloadSession session = openActiveSession();
        int processedTotal = 0;

        try (session) {
            while (processedTotal < downloadCount) {
                ensureNotCancelled();

                /*
                 * IMPORTANT: this is a materialization chunk, not the logical
                 * download window. processWindow() still enforces downloadWindow
                 * relative to nextToProcess. Keeping more indexes materialized
                 * lets that horizon slide across the old chunk boundary.
                 */
                int windowCount = Math.min(
                        BLOCK_INDEX_MATERIALIZATION_CHUNK,
                        downloadCount - processedTotal
                );

                long firstHeight = Math.addExact(
                        commonAncestor.height(),
                        (long) processedTotal + 1L
                );
                long lastHeight = Math.addExact(
                        firstHeight,
                        windowCount - 1L
                );

                List<BlockIndex> window = connectWindow(
                        bestHeaderTip,
                        firstHeight,
                        lastHeight
                );

                processWindow(session, window, targetHeight);

                if (collectResult) {
                    result.addAll(window);
                }
                processedTotal = Math.addExact(processedTotal, window.size());
            }

            if (session.pendingCount() != 0) {
                throw new IllegalStateException(
                        "Block synchronization processed its bounded path with "
                                + session.pendingCount() + " download(s) still pending"
                );
            }
        } finally {
            clearActiveSession(session);
            stallTracker.clear();
        }

        boolean complete = ((long) downloadCount) == remainingLong;
        if (complete) {
            BlockIndex finalTip = validationService.activeTip();
            while (finalTip.height() > bestHeaderTip.height()) {
                finalTip = Objects.requireNonNull(
                        lookup.find(finalTip.previousBlockHash()),
                        "Missing active ancestor"
                );
            }
            if (!finalTip.hash().equals(bestHeaderTip.hash())) {
                throw new IllegalStateException(
                        "Block synchronization completed without activating best header tip: expected "
                                + bestHeaderTip.hash().toDisplayHex()
                                + ", actual " + finalTip.hash().toDisplayHex()
                );
            }
        }

        return collectResult ? List.copyOf(result) : List.of();
    }

    /** Materializes only one forward connect window, never the complete IBD path. */
    private List<BlockIndex> connectWindow(
            BlockIndex bestHeaderTip,
            long firstHeight,
            long lastHeight
    ) {
        if (firstHeight < 1 || lastHeight < firstHeight || lastHeight > bestHeaderTip.height()) {
            throw new IllegalArgumentException(
                    "Invalid connect window " + firstHeight + ".." + lastHeight
                            + " for best header height " + bestHeaderTip.height()
            );
        }

        BlockIndex end = ancestorAtHeight(bestHeaderTip, lastHeight);
        int size = Math.toIntExact(lastHeight - firstHeight + 1L);
        ArrayList<BlockIndex> reversed = new ArrayList<>(size);
        BlockIndex current = end;

        while (true) {
            reversed.add(current);
            if (current.height() == firstHeight) {
                break;
            }
            BlockIndex parent = lookup.find(current.previousBlockHash());
            if (parent == null) {
                throw new IllegalStateException(
                        "Missing connect-window ancestor: "
                                + current.previousBlockHash().toDisplayHex()
                );
            }
            if (parent.height() != current.height() - 1L) {
                throw new IllegalStateException(
                        "Invalid connect-window ancestry at height " + current.height()
                );
            }
            current = parent;
        }

        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private BlockIndex ancestorAtHeight(BlockIndex index, long targetHeight) {
        if (lookup instanceof ru.bitcoin.node.chain.BlockIndexAncestorLookup ancestorLookup) {
            return ancestorLookup.ancestor(index, targetHeight);
        }
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            BlockIndex parent = lookup.find(current.previousBlockHash());
            if (parent == null) {
                throw new IllegalStateException(
                        "Missing ancestor for block " + current.hash().toDisplayHex()
                );
            }
            current = parent;
        }
        return current;
    }

    private void processWindow(
            BlockDownloadSession session,
            List<BlockIndex> blocksToDownload,
            long targetHeight
    ) throws IOException {
        Map<Hash256, AvailableBlock> availableBlocks = new HashMap<>();
        int nextToExpose = 0;
        int nextToProcess = 0;

        while (nextToProcess < blocksToDownload.size()) {
            int horizonEnd = Math.min(
                    blocksToDownload.size(),
                    Math.addExact(nextToProcess, downloadWindow)
            );

            List<BlockDownloadRequest> missingToSubmit = new ArrayList<>();
            while (nextToExpose < horizonEnd) {
                BlockIndex index = blocksToDownload.get(nextToExpose);
                Block localBlock = blockStore.find(index.hash()).orElse(null);
                if (localBlock != null) {
                    AvailableBlock previous = availableBlocks.put(
                            index.hash(), new AvailableBlock(localBlock, null));
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Block body became available more than once: "
                                        + index.hash().toDisplayHex());
                    }
                } else {
                    missingToSubmit.add(new BlockDownloadRequest(index.hash(), index.height()));
                }
                nextToExpose = Math.incrementExact(nextToExpose);
            }

            if (!missingToSubmit.isEmpty()) {
                session.submitRequests(missingToSubmit);
            }

            boolean processedAny = false;
            while (nextToProcess < blocksToDownload.size()) {
                BlockIndex index = blocksToDownload.get(nextToProcess);
                AvailableBlock available = availableBlocks.remove(index.hash());
                if (available == null) break;

                Block block = available.block();
                if (!block.hash().equals(index.hash())) {
                    throw new IllegalStateException(
                            "Available block does not match connect path: expected "
                                    + index.hash().toDisplayHex() + ", actual "
                                    + block.hash().toDisplayHex());
                }

                BlockProcessingResult processingResult = validationService.processBlock(block);
                if (processingResult == BlockProcessingResult.UNKNOWN_PARENT) {
                    throw new IllegalStateException(
                            "Block has unknown parent: " + index.hash().toDisplayHex()
                                    + " at height " + index.height());
                }
                if (processingResult == BlockProcessingResult.CONNECTED) {
                    connectedBlockListener.onConnected(block, available.sourcePeer());
                }

                nextToProcess = Math.incrementExact(nextToProcess);
                SyncProgressConsole.blocks(
                        index.height(), targetHeight, index.header().timestamp().value());
                processedAny = true;
            }

            if (processedAny) {
                /*
                 * Ordered progress opens a new slot at the tail of the logical
                 * download window. Return to the top immediately so that the
                 * newly exposed block is submitted before we wait for another
                 * completion or evaluate a stall.
                 *
                 * This is safe across the old window boundary because the
                 * materialized connect chunk is deliberately larger than the
                 * logical downloadWindow.
                 */
                stallTracker.update(null);
                continue;
            }

            /*
             * SchedulerBlockDownloadSession rejects pollCompleted() when there
             * is no outstanding request. A queued completion still counts as
             * pending until pollCompleted() consumes it, so this guard does not
             * hide a completed block.
             */
            if (session.pendingCount() == 0) {
                stallTracker.update(null);
                throw new IOException(
                        "No pending block downloads while synchronization is incomplete");
            }

            Optional<Peer> stallingPeer = stallDetector.findStallingPeer(
                    blocksToDownload,
                    nextToProcess,
                    downloadWindow,
                    index -> {
                        Hash256 hash = index.hash();
                        if (availableBlocks.containsKey(hash)) return true;
                        return blockStore.find(hash).isPresent();
                    },
                    index -> session.inFlightPeer(index.hash())
            );

            stallTracker.update(stallingPeer.orElse(null));
            BlockDownloadStallTimeoutEvaluator.Evaluation stallEvaluation =
                    stallTimeoutEvaluator.evaluate();

            if (stallEvaluation.timedOut()) {
                Peer timedOutPeer = stallEvaluation.peer();
                BlockIndex blockedIndex = blocksToDownload.get(nextToProcess);
                log.log(
                        System.Logger.Level.INFO,
                        "Block download stall: height={0}, hash={1}, peer={2}, age={3}, timeout={4}, pending={5}",
                        blockedIndex.height(),
                        blockedIndex.hash().toDisplayHex(),
                        timedOutPeer.remoteAddress(),
                        stallEvaluation.stallingAge(),
                        stallEvaluation.timeout(),
                        session.pendingCount()
                );
                IOException stallFailure = new IOException(
                        "Peer stalled block download window for "
                                + stallEvaluation.stallingAge() + " with timeout "
                                + stallEvaluation.timeout());
                session.failPeer(timedOutPeer, stallFailure);
                try {
                    timedOutPeer.close();
                } catch (IOException closeException) {
                    stallFailure.addSuppressed(closeException);
                }
                stallTimeoutEvaluator.timeoutHandled();
                stallTracker.clear(timedOutPeer);
                continue;
            }

            Optional<CompletedBlockDownload> completedOptional =
                    session.pollCompleted(DOWNLOAD_COMPLETION_POLL_INTERVAL);
            if (completedOptional.isEmpty()) {
                continue;
            }

            CompletedBlockDownload completed = completedOptional.get();
            Hash256 completedHash = completed.requestedHash();
            Block completedBlock = completed.block();
            if (!completedHash.equals(completedBlock.hash())) {
                throw new IllegalStateException(
                        "Completed block does not match requested hash: expected "
                                + completedHash.toDisplayHex() + ", actual "
                                + completedBlock.hash().toDisplayHex());
            }

            AvailableBlock previous = availableBlocks.put(
                    completedHash,
                    new AvailableBlock(completedBlock, completed.sourcePeer()));
            if (previous != null) {
                throw new IllegalStateException(
                        "Block body completed more than once: "
                                + completedHash.toDisplayHex());
            }
        }
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
