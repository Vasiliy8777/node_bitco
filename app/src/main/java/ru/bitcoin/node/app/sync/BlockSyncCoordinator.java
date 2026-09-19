package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.chain.ReorganizationPlan;
import ru.bitcoin.node.chain.ReorganizationPlanner;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.sync.BlockDownloadScheduler;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.block.BlockStore;

import java.io.IOException;
import java.util.*;

public final class BlockSyncCoordinator {
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
    private final int downloadWindow;
    private final BlockDownloadScheduler blockDownloadScheduler;
    private final NodeValidationService validationService;
    private final HeaderChainState headerChainState;
    private final BlockIndexLookup lookup;
    private final BlockStore blockStore;

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
                DEFAULT_DOWNLOAD_WINDOW
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

        if (downloadWindow <= 0) {
            throw new IllegalArgumentException(
                    "downloadWindow must be positive"
            );
        }

        this.downloadWindow =
                downloadWindow;
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
         * Download and connect the requested path in bounded windows.
         *
         * This prevents synchronize() from materializing the complete
         * remaining IBD path in memory before consensus processing.
         */
        for (int windowStart = 0;
             windowStart < blocksToDownload.size();
             windowStart += downloadWindow) {

            int windowEnd =
                    Math.min(
                            windowStart + downloadWindow,
                            blocksToDownload.size()
                    );

            List<BlockIndex> window =
                    blocksToDownload.subList(
                            windowStart,
                            windowEnd
                    );

            Map<Hash256, Block> availableBlocks =
                    new HashMap<>(
                            window.size()
                    );

            List<Hash256> missingBlockHashes =
                    new ArrayList<>();

            /*
             * Local-first remains mandatory.
             *
             * Bodies already present in BlockStore are used directly
             * and are not requested from peers.
             */
            for (BlockIndex index : window) {

                Block localBlock =
                        blockStore.find(
                                        index.hash()
                                )
                                .orElse(null);

                if (localBlock != null) {

                    availableBlocks.put(
                            index.hash(),
                            localBlock
                    );

                } else {

                    missingBlockHashes.add(
                            index.hash()
                    );
                }
            }

            /*
             * Only missing bodies from THIS window are exposed to
             * the network scheduler.
             */
            if (!missingBlockHashes.isEmpty()) {

                List<Block> downloadedBlocks =
                        blockDownloadScheduler.download(
                                missingBlockHashes
                        );

                if (downloadedBlocks.size()
                        != missingBlockHashes.size()) {

                    throw new IllegalStateException(
                            "Block download scheduler returned "
                                    + downloadedBlocks.size()
                                    + " block(s) for "
                                    + missingBlockHashes.size()
                                    + " requested hash(es)"
                    );
                }

                for (int i = 0;
                     i < missingBlockHashes.size();
                     i++) {

                    Hash256 expectedHash =
                            missingBlockHashes.get(i);

                    Block block =
                            downloadedBlocks.get(i);

                    if (!block.hash().equals(
                            expectedHash
                    )) {

                        throw new IllegalStateException(
                                "Block download scheduler returned unexpected block: "
                                        + "expected "
                                        + expectedHash.toDisplayHex()
                                        + ", actual "
                                        + block.hash().toDisplayHex()
                        );
                    }

                    availableBlocks.put(
                            expectedHash,
                            block
                    );
                }
            }

            /*
             * Consensus processing remains strictly ordered.
             *
             * The next download window is not started until every
             * block in this window has been processed.
             */
            for (BlockIndex index : window) {

                Block block =
                        availableBlocks.get(
                                index.hash()
                        );

                if (block == null) {

                    throw new IllegalStateException(
                            "Block body is unavailable after download: "
                                    + index.hash()
                                    .toDisplayHex()
                                    + " at height "
                                    + index.height()
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
            }
        }

        /*
         * Reaching bestHeaderTip is required only
         * when this invocation covered the complete
         * remaining connect path.
         *
         * A bounded synchronization call is allowed
         * to stop earlier so that IBD can be resumed
         * by a later invocation or after restart.
         */
        boolean complete =
                downloadCount
                        == blocksToConnect.size();

        if (complete) {

            BlockIndex finalTip =
                    validationService.activeTip();

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
}