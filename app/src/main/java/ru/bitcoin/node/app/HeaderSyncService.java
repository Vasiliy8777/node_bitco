package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.HeaderBatchProcessor;
import ru.bitcoin.node.p2p.message.HeadersMessage;

import java.util.List;
import java.util.function.Consumer;

public final class HeaderSyncService {

    private final HeaderBatchProcessor headerBatchProcessor;
    private final ru.bitcoin.node.app.sync.HeadersPresyncController presync;
    private final java.util.Set<ru.bitcoin.node.p2p.Peer> observed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public HeaderSyncService(
            HeaderBatchProcessor headerBatchProcessor
    ) {
        this(headerBatchProcessor, null);
    }

    public HeaderSyncService(HeaderBatchProcessor headerBatchProcessor,
            ru.bitcoin.node.app.sync.HeadersPresyncController presync) {
        if (headerBatchProcessor == null) {
            throw new IllegalArgumentException(
                    "headerBatchProcessor must not be null"
            );
        }

        this.headerBatchProcessor =
                headerBatchProcessor;
        this.presync = presync;
    }

    public record PeerReception(HeaderBatchProcessor.Reception reception,
            List<ru.bitcoin.node.common.types.Hash256> nextLocator, boolean presyncHandled) {
        public PeerReception { nextLocator = List.copyOf(nextLocator); }
    }

    public synchronized void forgetPeer(ru.bitcoin.node.p2p.Peer peer) {
        if (presync != null) presync.forget(peer);
        observed.remove(peer);
    }
    public boolean compactHasSufficientWork(ru.bitcoin.node.protocol.block.BlockHeader header) {
        return presync == null || presync.compactHasSufficientWork(header);
    }

    public long presyncedHeight(ru.bitcoin.node.p2p.Peer peer) {
        return presync == null ? -1 : presync.presyncedHeight(peer);
    }

    public synchronized PeerReception processPeerWithReception(ru.bitcoin.node.p2p.Peer peer,
            HeadersMessage message, Consumer<BlockIndex> onValidatedHeader, Runnable acknowledged) {
        java.util.Objects.requireNonNull(peer); java.util.Objects.requireNonNull(message);
        java.util.Objects.requireNonNull(onValidatedHeader); java.util.Objects.requireNonNull(acknowledged);
        if (presync == null) return new PeerReception(processWithReception(message, onValidatedHeader, acknowledged), List.of(), false);
        if (observed.add(peer)) peer.addCloseListener((closedPeer, failure) -> forgetPeer(closedPeer));
        var decision = presync.receive(peer, message.headers(), acknowledged);
        try {
            // REDOWNLOAD can release its complete bounded lookahead in one reply,
            // larger than a single wire message. Contextual checks still precede commit.
            var receipt = headerBatchProcessor.processWithReception(decision.ready(), onValidatedHeader);
            return new PeerReception(receipt, decision.nextLocator(), decision.handled());
        } catch (RuntimeException failure) {
            presync.forget(peer);
            throw failure;
        }
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
        return processWithReception(message, onValidatedHeader).indexes();
    }

    public synchronized HeaderBatchProcessor.Reception processWithReception(
            HeadersMessage message, Consumer<BlockIndex> onValidatedHeader) {
        return processWithReception(message, onValidatedHeader, () -> { });
    }

    public synchronized HeaderBatchProcessor.Reception processWithReception(
            HeadersMessage message, Consumer<BlockIndex> onValidatedHeader, Runnable onConnectingResponse) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (onValidatedHeader == null) {
            throw new IllegalArgumentException("onValidatedHeader must not be null");
        }
        var headers = message.headers();
        for (int i = 1; i < headers.size(); i++) {
            if (!headers.get(i).previousBlockHash().equals(headers.get(i - 1).hash())) {
                throw new IllegalArgumentException("Non-continuous headers response");
            }
        }
        return headerBatchProcessor.processWithReception(
                headers,
                onValidatedHeader,
                onConnectingResponse
        );
    }
}
