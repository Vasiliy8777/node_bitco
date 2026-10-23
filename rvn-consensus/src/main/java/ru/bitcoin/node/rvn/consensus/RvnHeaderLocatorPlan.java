package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Bitcoin-style exponentially spaced locator using ONLY validated header IDs.
 * Anchor ID is appended as the final fallback.
 */
public final class RvnHeaderLocatorPlan {
    private RvnHeaderLocatorPlan() {
    }

    public static List<byte[]> build(RvnValidatedHeaderIndex index,
                                     RvnHash256 tip, RvnHash256 anchor, int limit) {
        Objects.requireNonNull(index);
        Objects.requireNonNull(tip);
        Objects.requireNonNull(anchor);
        if (limit < 2 || limit > 101) throw new IllegalArgumentException("locator limit");
        List<RvnValidatedHeaderIndex.Entry> path = index.pathToAnchor(tip);
        List<byte[]> locators = new ArrayList<>();
        int step = 1;
        int cursor = path.size() - 1;
        while (cursor >= 0 && locators.size() < limit - 1) {
            locators.add(path.get(cursor).candidate().id().wire());
            if (locators.size() >= 10) step = Math.min(step * 2, Integer.MAX_VALUE / 2);
            cursor -= step;
        }
        if (locators.isEmpty() || !Arrays.equals(locators.get(locators.size() - 1), anchor.wire()))
            locators.add(anchor.wire());
        return List.copyOf(locators);
    }
}
