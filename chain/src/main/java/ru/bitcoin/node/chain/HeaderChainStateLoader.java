package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.ChainStateStore;

import java.util.Optional;

public final class HeaderChainStateLoader {

    private final BlockIndexStore blockIndexStore;
    private final ChainStateStore chainStateStore;

    public HeaderChainStateLoader(
            BlockIndexStore blockIndexStore,
            ChainStateStore chainStateStore
    ) {
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

        StoredBlockIndex stored =
                blockIndexStore
                        .find(hash)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Best header tip refers to missing block index: "
                                                        + hash.toDisplayHex()
                                        )
                        );

        BlockIndex bestHeaderTip =
                BlockIndexStorageMapper
                        .fromStored(
                                stored
                        );

        return Optional.of(
                new HeaderChainState(
                        bestHeaderTip
                )
        );
    }
}