package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.ArrayList;
import java.util.List;

public final class HeaderBatchProcessor {

    private final HeaderProcessor headerProcessor;
    private final HeaderChainState headerChainState;
    private final KnownHeaderStorage headerStorage;

    public HeaderBatchProcessor(
            HeaderProcessor headerProcessor,
            HeaderChainState headerChainState,
            KnownHeaderStorage headerStorage
    ) {
        if (headerProcessor == null) {
            throw new IllegalArgumentException(
                    "headerProcessor must not be null"
            );
        }

        if (headerChainState == null) {
            throw new IllegalArgumentException(
                    "headerChainState must not be null"
            );
        }

        if (headerStorage == null) {
            throw new IllegalArgumentException(
                    "headerStorage must not be null"
            );
        }

        this.headerProcessor =
                headerProcessor;

        this.headerChainState =
                headerChainState;

        this.headerStorage =
                headerStorage;
    }

    public List<BlockIndex> process(
            List<BlockHeader> headers
    ) {
        if (headers == null) {
            throw new IllegalArgumentException(
                    "headers must not be null"
            );
        }

        if (headers.stream().anyMatch(
                header -> header == null
        )) {
            throw new IllegalArgumentException(
                    "headers must not contain null"
            );
        }

        List<BlockIndex> processed =
                new ArrayList<>(
                        headers.size()
                );

        for (BlockHeader header : headers) {

            BlockIndex index =
                    headerProcessor.process(
                            header
                    );

            boolean better =
                    headerChainState.isBetterThanBest(
                            index
                    );

            /*
             * Persist first.
             *
             * If the candidate is the new best header,
             * the BlockIndex and best-header pointer
             * are committed in one RocksDB batch.
             */
            headerStorage.save(
                    index,
                    better
            );

            /*
             * Runtime state may move only after
             * persistent storage committed successfully.
             */
            if (better) {
                boolean changed =
                        headerChainState.consider(
                                index
                        );

                if (!changed) {
                    throw new IllegalStateException(
                            "Best header state changed unexpectedly"
                    );
                }
            }

            processed.add(
                    index
            );
        }

        return List.copyOf(
                processed
        );
    }
}