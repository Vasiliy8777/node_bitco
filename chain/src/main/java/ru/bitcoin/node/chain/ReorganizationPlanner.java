package ru.bitcoin.node.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReorganizationPlanner {

    private ReorganizationPlanner() {
    }

    public static ReorganizationPlan plan(
            BlockIndex currentTip,
            BlockIndex newTip,
            BlockIndexLookup lookup
    ) {
        if (currentTip == null) {
            throw new IllegalArgumentException(
                    "currentTip must not be null"
            );
        }

        if (newTip == null) {
            throw new IllegalArgumentException(
                    "newTip must not be null"
            );
        }

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        BlockIndex commonAncestor =
                CommonAncestorFinder.find(
                        currentTip,
                        newTip,
                        lookup
                );

        List<BlockIndex> blocksToDisconnect =
                collectDisconnectPath(
                        currentTip,
                        commonAncestor,
                        lookup
                );

        List<BlockIndex> blocksToConnect =
                collectConnectPath(
                        newTip,
                        commonAncestor,
                        lookup
                );

        return new ReorganizationPlan(
                commonAncestor,
                blocksToDisconnect,
                blocksToConnect
        );
    }

    private static List<BlockIndex> collectDisconnectPath(
            BlockIndex tip,
            BlockIndex ancestor,
            BlockIndexLookup lookup
    ) {
        List<BlockIndex> result =
                new ArrayList<>();

        BlockIndex current = tip;

        while (!current.hash().equals(
                ancestor.hash()
        )) {
            result.add(current);
            current = parentOf(
                    current,
                    lookup
            );
        }

        return result;
    }

    private static List<BlockIndex> collectConnectPath(
            BlockIndex tip,
            BlockIndex ancestor,
            BlockIndexLookup lookup
    ) {
        List<BlockIndex> result =
                new ArrayList<>();

        BlockIndex current = tip;

        while (!current.hash().equals(
                ancestor.hash()
        )) {
            result.add(current);
            current = parentOf(
                    current,
                    lookup
            );
        }

        /*
         * Сейчас список идёт:
         *
         * newTip -> ... -> ancestor
         *
         * Но подключать блоки нужно:
         *
         * ancestor -> ... -> newTip
         */
        Collections.reverse(result);

        return result;
    }

    private static BlockIndex parentOf(
            BlockIndex index,
            BlockIndexLookup lookup
    ) {
        if (index.height() == 0) {
            throw new IllegalStateException(
                    "Parent of genesis block requested"
            );
        }

        BlockIndex parent =
                lookup.find(
                        index.previousBlockHash()
                );

        if (parent == null) {
            throw new IllegalStateException(
                    "Parent block index not found: "
                            + index.previousBlockHash()
                            .toDisplayHex()
            );
        }

        return parent;
    }
}