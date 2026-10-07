package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.chain.ChainStateStore;

import java.util.Optional;

public final class HeaderChainStateLoader {

    private final BlockIndexStore blockIndexStore;
    private final ChainStateStore chainStateStore;
    private final BlockIndexLookup sharedLookup;

    public HeaderChainStateLoader(
            BlockIndexStore blockIndexStore,
            ChainStateStore chainStateStore
    ) {
        this(blockIndexStore, chainStateStore, null);
    }

    /** Shares immutable indexes; the selected persistent tip hash is still read on every refresh. */
    public HeaderChainStateLoader(BlockIndexStore blockIndexStore, ChainStateStore chainStateStore,
                                  BlockIndexLookup sharedLookup) {
        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.blockIndexStore =
                blockIndexStore;

        this.chainStateStore =
                chainStateStore;
        this.sharedLookup = sharedLookup;
    }

    public Optional<HeaderChainState> load() {

        Optional<Hash256> bestHeaderTipHash =
                chainStateStore
                        .loadBestHeaderTipHash();

        if (bestHeaderTipHash.isEmpty()) {
            return Optional.empty();
        }

        Hash256 hash =
                bestHeaderTipHash.orElseThrow();

        BlockIndex bestHeaderTip = requiredIndex(hash);

        return Optional.of(
                new HeaderChainState(
                        bestHeaderTip,
                        this::loadRequiredBestHeaderTip
                )
        );
    }

    private BlockIndex loadRequiredBestHeaderTip() {
        Hash256 hash = chainStateStore.loadBestHeaderTipHash()
                .orElseThrow(() -> new IllegalStateException(
                        "Best header state disappeared from persistent storage"));

        return requiredIndex(hash);
    }

    private BlockIndex requiredIndex(Hash256 hash) {
        BlockIndex index = sharedLookup == null
                ? blockIndexStore.find(hash).map(BlockIndexStorageMapper::fromStored).orElse(null)
                : sharedLookup.find(hash);
        if (index == null) throw new IllegalStateException(
                "Best header tip refers to missing block index: " + hash.toDisplayHex());
        return index;
    }
}
