package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.types.Hash256;

import java.util.Optional;

public interface ChainStateStore {

    Optional<Hash256> loadActiveTipHash();

    void saveActiveTipHash(
            Hash256 hash
    );

    Optional<Hash256> loadBestHeaderTipHash();

    void saveBestHeaderTipHash(
            Hash256 hash
    );
}