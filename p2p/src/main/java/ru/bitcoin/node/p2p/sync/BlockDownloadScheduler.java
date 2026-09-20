package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BlockDownloadScheduler {

    static final Duration COMPLETION_CHECK_INTERVAL =
            Duration.ofMillis(
                    250
            );

    public static final int MAX_BLOCKS_IN_FLIGHT_PER_PEER =
            BlockInFlightTracker
                    .DEFAULT_MAX_BLOCKS_PER_PEER;

    private final PeerManager peerManager;

    private final BlockDownloadService blockDownloadService;

    private final BlockDownloadTimeoutPolicy timeoutPolicy;

    public BlockDownloadScheduler(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {

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

    public BlockDownloadSession openSession() {

        return new SchedulerBlockDownloadSession(
                peerManager,
                blockDownloadService,
                timeoutPolicy
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