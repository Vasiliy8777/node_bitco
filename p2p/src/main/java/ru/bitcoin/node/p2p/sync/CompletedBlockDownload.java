package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;

import java.util.Objects;

public record CompletedBlockDownload(
        int index,
        Hash256 requestedHash,
        Block block
) {

    public CompletedBlockDownload {

        if (index < 0) {
            throw new IllegalArgumentException(
                    "index must not be negative"
            );
        }

        Objects.requireNonNull(
                requestedHash,
                "requestedHash"
        );

        Objects.requireNonNull(
                block,
                "block"
        );

        if (!requestedHash.equals(
                block.hash()
        )) {
            throw new IllegalArgumentException(
                    "Downloaded block hash does not match requested hash: "
                            + "expected "
                            + requestedHash.toDisplayHex()
                            + ", actual "
                            + block.hash().toDisplayHex()
            );
        }
    }
}