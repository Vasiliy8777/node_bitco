package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.HeaderBatchProcessor;
import ru.bitcoin.node.p2p.message.HeadersMessage;

import java.util.List;
import java.util.function.Consumer;

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

    public synchronized List<BlockIndex> process(
            HeadersMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return process(message, ignored -> { });
    }

    public synchronized List<BlockIndex> process(
            HeadersMessage message,
            Consumer<BlockIndex> onValidatedHeader
    ) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (onValidatedHeader == null) {
            throw new IllegalArgumentException("onValidatedHeader must not be null");
        }
        return headerBatchProcessor.process(
                message.headers(),
                onValidatedHeader
        );
    }
}
