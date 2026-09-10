package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;

public interface BlockIndexLookup {

    BlockIndex find(Hash256 hash);
}