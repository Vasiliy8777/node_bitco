package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.block.BlockIndexStore;

import java.util.ArrayList;
import java.util.List;

public final class HeaderBatchProcessor {

    private final HeaderProcessor headerProcessor;
    private final BlockIndexStore blockIndexStore;

    public HeaderBatchProcessor(
            HeaderProcessor headerProcessor,
            BlockIndexStore blockIndexStore
    ) {
        if (headerProcessor == null) {
            throw new IllegalArgumentException(
                    "headerProcessor must not be null"
            );
        }

        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        this.headerProcessor =
                headerProcessor;

        this.blockIndexStore =
                blockIndexStore;
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

            blockIndexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index
                    )
            );

            processed.add(
                    index
            );
        }

        return List.copyOf(
                processed
        );
    }
}