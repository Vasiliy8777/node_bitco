package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockFailureStore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class BlockFailureResolver {

    private final BlockIndexLookup lookup;
    private final BlockFailureStore failureStore;

    public BlockFailureResolver(
            BlockIndexLookup lookup,
            BlockFailureStore failureStore
    ) {
        this.lookup =
                Objects.requireNonNull(
                        lookup,
                        "lookup"
                );

        this.failureStore =
                Objects.requireNonNull(
                        failureStore,
                        "failureStore"
                );
    }

    public boolean isFailed(
            BlockIndex index
    ) {
        Objects.requireNonNull(
                index,
                "index"
        );

        return isFailed(index, null);
    }

    public boolean isFailed(
            BlockIndex index,
            Hash256 additionallyFailed
    ) {
        Objects.requireNonNull(index, "index");

        BlockIndex current =
                index;

        Set<ru.bitcoin.node.common.types.Hash256> visited =
                new HashSet<>();

        while (true) {

            if (!visited.add(
                    current.hash()
            )) {
                throw new IllegalStateException(
                        "Cycle detected in block-index ancestry at "
                                + current.hash().toDisplayHex()
                );
            }

            if ((additionallyFailed != null
                    && additionallyFailed.equals(current.hash()))
                    || failureStore.isFailed(
                    current.hash()
            )) {
                return true;
            }

            if (current.height() == 0) {
                return false;
            }

            BlockIndex parent =
                    lookup.find(
                            current.previousBlockHash()
                    );

            if (parent == null) {
                throw new IllegalStateException(
                        "Missing BlockIndex ancestor while resolving failure state: "
                                + current.previousBlockHash()
                                .toDisplayHex()
                );
            }

            current =
                    parent;
        }
    }
}