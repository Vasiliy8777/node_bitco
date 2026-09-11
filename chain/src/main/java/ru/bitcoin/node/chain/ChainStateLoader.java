package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.ChainStateStore;

import java.util.Optional;

public final class ChainStateLoader {

    private final BlockIndexStore blockIndexStore;
    private final ChainStateStore chainStateStore;

    public ChainStateLoader(
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

    public Optional<ChainState> load() {

        Optional<Hash256> activeTipHash =
                chainStateStore
                        .loadActiveTipHash();

        if (activeTipHash.isEmpty()) {
            return Optional.empty();
        }

        Hash256 hash =
                activeTipHash.orElseThrow();

        StoredBlockIndex stored =
                blockIndexStore
                        .find(hash)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Active chain tip refers to missing block index: "
                                                        + hash.toDisplayHex()
                                        )
                        );

        BlockIndex activeTip =
                BlockIndexStorageMapper
                        .fromStored(stored);

        return Optional.of(
                new ChainState(
                        activeTip
                )
        );
    }
}