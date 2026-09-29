package ru.bitcoin.node.chain;

@FunctionalInterface
public interface InvalidBlockObserver {
    void onInvalidBlock(BlockIndex blockIndex);

    static InvalidBlockObserver noop() {
        return blockIndex -> { };
    }
}
