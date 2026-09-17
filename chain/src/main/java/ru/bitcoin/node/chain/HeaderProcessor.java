package ru.bitcoin.node.chain;

import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class HeaderProcessor {

    private final BlockIndexLookup blockIndexLookup;
    private final NetworkParameters networkParameters;
    private final AdjustedTime adjustedTime;

    public HeaderProcessor(
            BlockIndexLookup blockIndexLookup,
            NetworkParameters networkParameters,
            AdjustedTime adjustedTime
    ) {
        if (blockIndexLookup == null) {
            throw new IllegalArgumentException(
                    "blockIndexLookup must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        if (adjustedTime == null) {
            throw new IllegalArgumentException(
                    "adjustedTime must not be null"
            );
        }

        this.blockIndexLookup =
                blockIndexLookup;

        this.networkParameters =
                networkParameters;

        this.adjustedTime =
                adjustedTime;
    }

    public BlockIndex process(
            BlockHeader header
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        BlockIndex existing =
                blockIndexLookup.find(
                        header.hash()
                );

        if (existing != null) {
            return existing;
        }

        BlockIndex parent =
                blockIndexLookup.find(
                        header.previousBlockHash()
                );

        if (parent == null) {
            throw new IllegalStateException(
                    "Parent header is unknown: "
                            + header.previousBlockHash()
                            .toDisplayHex()
            );
        }

        ChainHeaderValidator.validate(
                header,
                parent,
                blockIndexLookup,
                networkParameters,
                adjustedTime
        );

        return BlockIndexFactory.createChild(
                parent,
                header
        );
    }
}