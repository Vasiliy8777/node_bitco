package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        this(headerProcessor, headerChainState, headerStorage, null);
    }

    public HeaderBatchProcessor(
            HeaderProcessor headerProcessor,
            HeaderChainState headerChainState,
            KnownHeaderStorage headerStorage,
            BlockFailureResolver failureResolver
    ) {
        if (headerProcessor == null) throw new IllegalArgumentException("headerProcessor must not be null");
        if (headerChainState == null) throw new IllegalArgumentException("headerChainState must not be null");
        if (headerStorage == null) throw new IllegalArgumentException("headerStorage must not be null");
        this.headerProcessor = headerProcessor;
        this.headerChainState = headerChainState;
        this.headerStorage = headerStorage;
        this.failureResolver = failureResolver;
    }

    public List<BlockIndex> process(List<BlockHeader> headers) {
        return process(headers, ignored -> { });
    }

    /**
     * Validates one network HEADERS message against a batch-local overlay and commits
     * all newly discovered indexes with one durable RocksDB WriteBatch.
     */
    public List<BlockIndex> process(
            List<BlockHeader> headers,
            Consumer<BlockIndex> onValidatedHeader
    ) {
        if (headers == null) throw new IllegalArgumentException("headers must not be null");
        if (headers.stream().anyMatch(header -> header == null)) {
            throw new IllegalArgumentException("headers must not contain null");
        }
        if (onValidatedHeader == null) throw new IllegalArgumentException("onValidatedHeader must not be null");
        if (headers.isEmpty()) return List.of();

        Map<Hash256, BlockIndex> overlay = new HashMap<>(Math.max(16, headers.size() * 2));
        BlockIndexLookup baseLookup = headerProcessor.baseLookup();
        BlockIndexLookup effectiveLookup = hash -> {
            BlockIndex local = overlay.get(hash);
            return local != null ? local : baseLookup.find(hash);
        };

        List<BlockIndex> processed = new ArrayList<>(headers.size());
        List<BlockIndex> newlyCreated = new ArrayList<>(headers.size());
        BlockIndex initialBest = headerChainState.bestHeaderTip();
        BlockIndex candidateBest = initialBest;

        for (BlockHeader header : headers) {
            HeaderProcessor.ProcessResult result =
                    headerProcessor.processDetailed(header, effectiveLookup);
            BlockIndex index = result.index();

            if (result.newlyCreated()) {
                overlay.put(index.hash(), index);
                newlyCreated.add(index);
            }

            boolean failed = failureResolver != null
                    && failureResolver.isFailed(index, null, effectiveLookup);
            if (!failed && index.chainWork().compareTo(candidateBest.chainWork()) > 0) {
                candidateBest = index;
            }

            processed.add(index);
            onValidatedHeader.accept(index);
        }

        boolean bestChanged = !candidateBest.hash().equals(initialBest.hash());
        headerStorage.saveBatch(newlyCreated, bestChanged ? candidateBest : null);

        if (bestChanged) {
            boolean changed = headerChainState.consider(candidateBest);
            if (!changed) {
                throw new IllegalStateException("Best header state changed unexpectedly");
            }
        }

        return List.copyOf(processed);
    }
}
