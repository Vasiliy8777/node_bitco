package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.ArrayList;
import java.util.List;

public final class HeaderBatchProcessor {

    private final HeaderProcessor headerProcessor;
    private final HeaderChainState headerChainState;
    private final KnownHeaderStorage headerStorage;
    private final BlockFailureResolver failureResolver;

    public HeaderBatchProcessor(
            HeaderProcessor headerProcessor,
            HeaderChainState headerChainState,
            KnownHeaderStorage headerStorage
    ) {
        this(
                headerProcessor,
                headerChainState,
                headerStorage,
                null
        );
    }

    public HeaderBatchProcessor(
            HeaderProcessor headerProcessor,
            HeaderChainState headerChainState,
            KnownHeaderStorage headerStorage,
            BlockFailureResolver failureResolver
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

        this.failureResolver =
                failureResolver;
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

        for (int headerIndex = 0; headerIndex < headers.size(); headerIndex++) {

            BlockHeader header = headers.get(headerIndex);

            BlockIndex index =
                    headerProcessor.process(
                            header
                    );

            boolean failed =
                    failureResolver != null
                            && failureResolver.isFailed(index);

            boolean better =
                    !failed
                            && headerChainState.isBetterThanBest(
                            index
                    );

            /*
             * Persist first.
             *
             * If the candidate is the new best header,
             * the BlockIndex and best-header pointer
             * are committed in one RocksDB batch.
             */
            /*
             * A HEADERS message may contain up to 2,000 entries. Performing a
             * synchronous WAL flush for every individual header makes real-network
             * IBD disk-latency bound. Preserve the existing per-header visibility
             * (the next header must be able to resolve its parent), but defer the
             * expensive durable flush until the final header in this validated
             * network batch. The final sync=true RocksDB write flushes the WAL
             * containing all preceding sync=false writes.
             */
            boolean durable = headerIndex == headers.size() - 1;

            headerStorage.save(
                    index,
                    better,
                    durable
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