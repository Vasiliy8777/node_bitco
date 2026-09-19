package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;

import java.io.IOException;
import java.util.Objects;

public final class BlockNotFoundException
        extends IOException {

    private final Hash256 blockHash;

    public BlockNotFoundException(
            Hash256 blockHash
    ) {
        super(
                "Peer does not have requested block "
                        + Objects.requireNonNull(
                        blockHash,
                        "blockHash"
                ).toDisplayHex()
        );

        this.blockHash =
                blockHash;
    }

    public Hash256 blockHash() {
        return blockHash;
    }
}