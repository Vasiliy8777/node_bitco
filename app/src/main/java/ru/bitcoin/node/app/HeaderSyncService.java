package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.HeaderBatchProcessor;
import ru.bitcoin.node.p2p.message.HeadersMessage;

import java.util.List;

public final class HeaderSyncService {

    private final HeaderBatchProcessor headerBatchProcessor;

    public HeaderSyncService(
            HeaderBatchProcessor headerBatchProcessor
    ) {
        if (headerBatchProcessor == null) {
            throw new IllegalArgumentException(
                    "headerBatchProcessor must not be null"
            );
        }

        this.headerBatchProcessor =
                headerBatchProcessor;
    }

    public List<BlockIndex> process(
            HeadersMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return headerBatchProcessor.process(
                message.headers()
        );
    }
}