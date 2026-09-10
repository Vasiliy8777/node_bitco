package ru.bitcoin.node.chain;

import java.util.List;

public record ReorganizationPlan(
        BlockIndex commonAncestor,
        List<BlockIndex> blocksToDisconnect,
        List<BlockIndex> blocksToConnect
) {
    public ReorganizationPlan {
        if (commonAncestor == null) {
            throw new IllegalArgumentException(
                    "commonAncestor must not be null"
            );
        }

        if (blocksToDisconnect == null) {
            throw new IllegalArgumentException(
                    "blocksToDisconnect must not be null"
            );
        }

        if (blocksToConnect == null) {
            throw new IllegalArgumentException(
                    "blocksToConnect must not be null"
            );
        }

        blocksToDisconnect =
                List.copyOf(blocksToDisconnect);

        blocksToConnect =
                List.copyOf(blocksToConnect);
    }
}