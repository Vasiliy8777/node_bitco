package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;

@FunctionalInterface
public interface InvalidBlockObserver {
    void onInvalidBlock(Hash256 blockHash);

    static InvalidBlockObserver noop() {
        return blockHash -> { };
    }
}
