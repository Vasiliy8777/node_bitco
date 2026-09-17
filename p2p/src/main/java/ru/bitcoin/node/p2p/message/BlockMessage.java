package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.protocol.block.Block;

import java.util.Objects;

public record BlockMessage(
        Block block
) {

    public BlockMessage {
        Objects.requireNonNull(
                block,
                "block"
        );
    }
}