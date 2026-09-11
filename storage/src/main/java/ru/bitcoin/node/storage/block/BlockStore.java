package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;

import java.util.Optional;

public interface BlockStore {

    void save(Block block);

    Optional<Block> find(Hash256 blockHash);

    void delete(Hash256 blockHash);
}