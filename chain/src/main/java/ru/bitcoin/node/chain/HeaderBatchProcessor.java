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
        Map<Hash256, BlockIndex> readCache = new HashMap<>(64);
        BlockIndexLookup baseLookup = headerProcessor.baseLookup();
        BlockIndexLookup effectiveLookup = new BlockIndexAncestorLookup() {
            @Override
            public BlockIndex find(Hash256 hash) {
                BlockIndex local = overlay.get(hash);
                if (local != null) return local;
                if (readCache.containsKey(hash)) return readCache.get(hash);
                BlockIndex loaded = baseLookup.find(hash);
                readCache.put(hash, loaded);
                return loaded;
            }

            @Override
            public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                if (targetHeight < 0 || targetHeight > index.height()) {
                    throw new IllegalArgumentException("Invalid ancestor height: " + targetHeight);
                }
                BlockIndex current = index;
                while (current.height() > targetHeight && overlay.containsKey(current.hash())) {
                    current = find(current.previousBlockHash());
                    if (current == null) throw new IllegalStateException("Missing batch ancestor");
                }
                if (current.height() == targetHeight) return current;
                if (baseLookup instanceof BlockIndexAncestorLookup ancestorLookup) {
                    return ancestorLookup.ancestor(current, targetHeight);
                }
                while (current.height() > targetHeight) {
                    current = find(current.previousBlockHash());
                    if (current == null) throw new IllegalStateException("Missing ancestor");
                }
                return current;
            }
        };

        List<BlockIndex> processed = new ArrayList<>(headers.size());
        List<BlockIndex> newlyCreated = new ArrayList<>(headers.size());
        Map<Hash256, Hash256> resolvedSkipHashes = new HashMap<>(Math.max(16, headers.size() * 2));
        BlockIndex initialBest = headerChainState.bestHeaderTip();
        BlockIndex candidateBest = initialBest;
        BlockIndex previousBatchNew = null;

        for (BlockHeader header : headers) {
            HeaderProcessor.ProcessResult result;
            if (previousBatchNew != null
                    && header.previousBlockHash().equals(previousBatchNew.hash())
                    && !overlay.containsKey(header.hash())) {
                result = headerProcessor.processNewWithKnownBatchParent(
                        header, previousBatchNew, effectiveLookup);
            } else {
                result = headerProcessor.processDetailed(header, effectiveLookup);
            }
            BlockIndex index = result.index();

            if (result.newlyCreated()) {
                overlay.put(index.hash(), index);
                newlyCreated.add(index);
                if (index.height() > 0) {
                    long skipHeight = ru.bitcoin.node.storage.block.RocksDbBlockIndexStore
                            .getSkipHeight(index.height());
                    BlockIndex skipAncestor = ((BlockIndexAncestorLookup) effectiveLookup)
                            .ancestor(index, skipHeight);
                    resolvedSkipHashes.put(index.hash(), skipAncestor.hash());
                }
                previousBatchNew = index;
            } else {
                previousBatchNew = null;
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
        headerStorage.saveBatch(newlyCreated, bestChanged ? candidateBest : null, resolvedSkipHashes);

        if (bestChanged) {
            boolean changed = headerChainState.consider(candidateBest);
            if (!changed) {
                throw new IllegalStateException("Best header state changed unexpectedly");
            }
        }

        return List.copyOf(processed);
    }
}
