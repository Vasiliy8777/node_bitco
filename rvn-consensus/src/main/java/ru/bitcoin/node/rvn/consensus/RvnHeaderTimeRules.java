package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Median of available VERIFIED ancestor timestamps, up to 11.
 * Caller must supply a verified anchor timestamp if validation reaches the anchor.
 */
public final class RvnHeaderTimeRules {
    private RvnHeaderTimeRules() {
    }

    public static long medianTimePast(RvnValidatedHeaderIndex index, RvnHash256 parent,
                                      RvnHash256 anchor, long anchorTimestamp) {
        Objects.requireNonNull(index);
        Objects.requireNonNull(parent);
        Objects.requireNonNull(anchor);
        if (anchorTimestamp < 0 || anchorTimestamp > 0xffff_ffffL)
            throw new IllegalArgumentException("anchor timestamp");
        List<Long> times = new ArrayList<>(11);
        RvnHash256 cursor = parent;
        for (int i = 0; i < 11; i++) {
            if (cursor.equals(anchor)) {
                times.add(anchorTimestamp);
                break;
            }
            var entry = index.find(cursor).orElseThrow(() -> new IllegalArgumentException("Unknown verified ancestor"));
            times.add(entry.candidate().timestamp());
            cursor = entry.candidate().parentId();
        }
        Collections.sort(times);
        return times.get(times.size() / 2);
    }

    public static void requireTimestamp(long candidateTime, long medianTimePast, long nowSeconds,
                                        long allowedFutureDriftSeconds) {
        if (candidateTime < 0 || candidateTime > 0xffff_ffffL || nowSeconds < 0
                || allowedFutureDriftSeconds < 0 || allowedFutureDriftSeconds > 86400)
            throw new IllegalArgumentException("Invalid timestamp parameters");
        if (candidateTime <= medianTimePast)
            throw new SecurityException("Header timestamp not above median time past");
        long max = nowSeconds > Long.MAX_VALUE - allowedFutureDriftSeconds
                ? Long.MAX_VALUE : nowSeconds + allowedFutureDriftSeconds;
        if (candidateTime > max)
            throw new SecurityException("Header timestamp too far in future");
    }
}
