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

        /*
         * Fast path for the overwhelmingly common IBD case: the new best block is
         * the direct child of the current active tip.  The generic planner would
         * otherwise ask CommonAncestorFinder to resolve newTip -> currentTip through
         * BlockIndexAncestorLookup and then walk the same edge again while building
         * blocksToConnect.  With the RocksDB lookup that means an unnecessary
         * blockSkipIndex read (plus parent lookup) for every connected IBD block.
         *
         * Parent hash + adjacent height prove the complete transition here; no chain
         * history is being skipped.  Reorganizations, side branches and height jumps
         * continue through the branch-safe generic planner below unchanged.
         */
        if (newTip.height() == currentTip.height() + 1L
                && newTip.previousBlockHash().equals(currentTip.hash())) {
            return new ReorganizationPlan(
                    currentTip,
                    List.of(),
                    List.of(newTip)
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