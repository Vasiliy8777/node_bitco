package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Immutable plan for moving between TWO ALREADY VERIFIED branches.
 * This is not a UTXO reorganization executor.
 */
public record RvnChainTransition(RvnHash256 ancestor,
                                 List<RvnValidatedHeaderIndex.Entry> disconnect,
                                 List<RvnValidatedHeaderIndex.Entry> connect) {
    public RvnChainTransition {
        Objects.requireNonNull(ancestor);
        disconnect = List.copyOf(disconnect);
        connect = List.copyOf(connect);
    }

    public static RvnChainTransition plan(RvnValidatedHeaderIndex index,
                                          RvnHash256 oldTip, RvnHash256 newTip) {
        Objects.requireNonNull(index);
        RvnHash256 ancestor = index.commonAncestor(oldTip, newTip);
        List<RvnValidatedHeaderIndex.Entry> oldPath = index.pathToAnchor(oldTip);
        List<RvnValidatedHeaderIndex.Entry> newPath = index.pathToAnchor(newTip);
        List<RvnValidatedHeaderIndex.Entry> disconnect = new ArrayList<>();
        for (int i = oldPath.size() - 1; i >= 0; i--) {
            var entry = oldPath.get(i);
            if (entry.candidate().id().equals(ancestor)) break;
            disconnect.add(entry);
        }
        List<RvnValidatedHeaderIndex.Entry> connect = new ArrayList<>();
        boolean pastAncestor = newPath.stream().noneMatch(e -> e.candidate().id().equals(ancestor));
        for (var entry : newPath) {
            if (entry.candidate().id().equals(ancestor)) {
                pastAncestor = true;
                continue;
            }
            if (pastAncestor) connect.add(entry);
        }
        return new RvnChainTransition(ancestor, disconnect, connect);
    }
}
