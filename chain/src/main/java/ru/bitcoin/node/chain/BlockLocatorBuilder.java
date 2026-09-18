package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BlockLocatorBuilder {

    private static final int INITIAL_CAPACITY = 32;

    private final BlockIndexLookup blockIndexLookup;

    public BlockLocatorBuilder(
            BlockIndexLookup blockIndexLookup
    ) {
        this.blockIndexLookup =
                Objects.requireNonNull(
                        blockIndexLookup,
                        "blockIndexLookup"
                );
    }

    public List<Hash256> build(
            BlockIndex tip
    ) {
        Objects.requireNonNull(
                tip,
                "tip"
        );

        List<Hash256> locator =
                new ArrayList<>(
                        INITIAL_CAPACITY
                );

        BlockIndex current =
                tip;

        int step = 1;

        while (true) {

            locator.add(
                    current.hash()
            );

            if (current.height() == 0) {
                break;
            }

            long targetHeight =
                    Math.max(
                            current.height() - step,
                            0L
                    );

            current =
                    ancestor(
                            current,
                            targetHeight
                    );

            /*
             * Bitcoin Core LocatorEntries():
             *
             * if (have.size() > 10)
             *     step *= 2;
             */
            if (locator.size() > 10) {
                step *= 2;
            }
        }

        return List.copyOf(
                locator
        );
    }

    private BlockIndex ancestor(
            BlockIndex index,
            long targetHeight
    ) {
        if (targetHeight < 0
                || targetHeight > index.height()) {

            throw new IllegalArgumentException(
                    "Invalid ancestor height: "
                            + targetHeight
            );
        }

        BlockIndex current =
                index;

        while (current.height() > targetHeight) {

            BlockIndex parent =
                    blockIndexLookup.find(
                            current.previousBlockHash()
                    );

            if (parent == null) {
                throw new IllegalStateException(
                        "Missing ancestor for block "
                                + current.hash()
                                .toDisplayHex()
                                + " at height "
                                + current.height()
                );
            }

            if (parent.height()
                    != current.height() - 1) {

                throw new IllegalStateException(
                        "Invalid ancestor height: expected "
                                + (current.height() - 1)
                                + " but found "
                                + parent.height()
                );
            }

            current =
                    parent;
        }

        return current;
    }
}