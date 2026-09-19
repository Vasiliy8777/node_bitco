package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockLocatorBuilder;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HeaderSyncCoordinator {

    private final HeaderSynchronizer headerSynchronizer;
    private final HeaderSyncService headerSyncService;
    private final HeaderChainState headerChainState;
    private final BlockLocatorBuilder blockLocatorBuilder;

    public HeaderSyncCoordinator(
            HeaderSynchronizer headerSynchronizer,
            HeaderSyncService headerSyncService,
            HeaderChainState headerChainState,
            BlockLocatorBuilder blockLocatorBuilder
    ) {
        this.headerSynchronizer =
                Objects.requireNonNull(
                        headerSynchronizer,
                        "headerSynchronizer"
                );

        this.headerSyncService =
                Objects.requireNonNull(
                        headerSyncService,
                        "headerSyncService"
                );

        this.headerChainState =
                Objects.requireNonNull(
                        headerChainState,
                        "headerChainState"
                );

        this.blockLocatorBuilder =
                Objects.requireNonNull(
                        blockLocatorBuilder,
                        "blockLocatorBuilder"
                );
    }

    public List<BlockIndex> synchronize(Hash256 stopHash)
            throws IOException {

        Objects.requireNonNull(
                stopHash,
                "stopHash"
        );

        List<BlockIndex> allProcessed =
                new ArrayList<>();

        BlockIndex cursor =
                headerChainState.bestHeaderTip();

        while (true) {

            BlockIndex previousCursor =
                    cursor;

            List<Hash256> locator =
                    blockLocatorBuilder.build(
                            previousCursor
                    );

            HeadersMessage headers =
                    headerSynchronizer.download(
                            locator,
                            stopHash
                    );

            if (headers.isEmpty()) {
                return List.copyOf(
                        allProcessed
                );
            }

            List<BlockIndex> processed =
                    headerSyncService.process(
                            headers
                    );

            if (processed.isEmpty()) {
                throw new IllegalStateException(
                        "Non-empty headers response "
                                + "produced no block indexes"
                );
            }

            cursor =
                    processed.get(
                            processed.size() - 1
                    );

            if (cursor.hash().equals(
                    previousCursor.hash()
            )) {
                throw new IllegalStateException(
                        "Non-empty headers response made "
                                + "no synchronization progress: "
                                + cursor.hash().toDisplayHex()
                );
            }

            allProcessed.addAll(
                    processed
            );
        }
    }
}