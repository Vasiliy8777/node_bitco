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
        return processDetailed(header, blockIndexLookup).index();
    }

    ProcessResult processDetailed(
            BlockHeader header,
            BlockIndexLookup lookup
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        BlockIndex existing =
                lookup.find(
                        header.hash()
                );

        if (existing != null) {
            return new ProcessResult(existing, false);
        }

        BlockIndex parent =
                lookup.find(
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
                lookup,
                networkParameters,
                adjustedTime
        );

        return new ProcessResult(
                BlockIndexFactory.createChild(
                        parent,
                        header
                ),
                true
        );
    }

    /**
     * Fast path for a header whose parent was created earlier in the same HEADERS batch.
     * The caller must only use this when the parent is batch-local. In that case a durable
     * child index cannot already exist in a consistent store, because its parent itself was
     * not durable before this batch. All contextual/PoW validation remains identical.
     */
    ProcessResult processNewWithKnownBatchParent(
            BlockHeader header,
            BlockIndex parent,
            BlockIndexLookup lookup
    ) {
        if (header == null || parent == null || lookup == null) {
            throw new IllegalArgumentException("fast-path arguments must not be null");
        }
        if (!header.previousBlockHash().equals(parent.hash())) {
            throw new IllegalArgumentException("Header previous block hash does not match batch parent");
        }

        ChainHeaderValidator.validate(
                header,
                parent,
                lookup,
                networkParameters,
                adjustedTime
        );

        return new ProcessResult(
                BlockIndexFactory.createChild(parent, header),
                true
        );
    }

    BlockIndexLookup baseLookup() {
        return blockIndexLookup;
    }

    record ProcessResult(BlockIndex index, boolean newlyCreated) {
    }
}