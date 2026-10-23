package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.Objects;

/** Mainnet historical retargeting, including DGW and the KawPoW transition.
 * Source: RavenProject/Ravencoin src/pow.cpp and src/chainparams.cpp.
 * Ancestors MUST come from a validated branch, never a peer-supplied height map.
 */
public final class RvnMainnetDifficulty {
    public static final long DGW_HEIGHT = 338778;
    public static final long KAWPOW_TIME = 1588788000L;
    public static final BigInteger POW_LIMIT = new BigInteger(
            "00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    public static final BigInteger KAWPOW_START_LIMIT = new BigInteger(
            "0000000000ffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    private static final BigInteger MASK = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private static final long SPACING = 60, INTERVAL = 2016, TIMESPAN = SPACING * INTERVAL;

    private RvnMainnetDifficulty() {}

    public record Ancestor(long height, long timestamp, long bits) {
        public Ancestor {
            if (height < 0 || timestamp < 0 || timestamp > 0xffff_ffffL)
                throw new IllegalArgumentException("Invalid ancestor");
            if (CompactTarget.decode(bits).compareTo(POW_LIMIT) > 0)
                throw new IllegalArgumentException("Ancestor target exceeds powLimit");
        }
    }

    @FunctionalInterface
    public interface History {
        /** distance 0 is parent, 1 is grandparent; must stay on the parent branch. */
        Ancestor ancestor(int distance);
    }

    public static long expected(long candidateTime, History history) {
        if (candidateTime < 0 || candidateTime > 0xffff_ffffL)
            throw new IllegalArgumentException("Timestamp must be uint32");
        Objects.requireNonNull(history);
        Ancestor parent = Objects.requireNonNull(history.ancestor(0), "Missing parent");
        long height = Math.addExact(parent.height(), 1);
        if (height < DGW_HEIGHT) {
            if (height % INTERVAL != 0) return parent.bits();
            Ancestor first = checked(history, parent, (int) INTERVAL - 1);
            long elapsed = clamp(parent.timestamp() - first.timestamp(), TIMESPAN / 4, TIMESPAN * 4);
            return scaled(CompactTarget.decode(parent.bits()), elapsed, TIMESPAN);
        }
        BigInteger average = BigInteger.ZERO;
        int kawpowBlocks = 0;
        Ancestor oldest = parent;
        for (int count = 1; count <= 180; count++) {
            oldest = count == 1 ? parent : checked(history, parent, count - 1);
            BigInteger target = CompactTarget.decode(oldest.bits());
            // Core deliberately uses count and count+1, not an arithmetic mean.
            average = count == 1 ? target : average.multiply(BigInteger.valueOf(count))
                    .add(target).and(MASK).divide(BigInteger.valueOf(count + 1));
            if (oldest.timestamp() >= KAWPOW_TIME) kawpowBlocks++;
        }
        if (candidateTime >= KAWPOW_TIME && kawpowBlocks != 180)
            return CompactTarget.encode(KAWPOW_START_LIMIT);
        long targetSpan = 180 * SPACING;
        long elapsed = clamp(parent.timestamp() - oldest.timestamp(), targetSpan / 3, targetSpan * 3);
        return scaled(average, elapsed, targetSpan);
    }

    private static Ancestor checked(History history, Ancestor parent, int distance) {
        Ancestor ancestor = Objects.requireNonNull(history.ancestor(distance), "Missing difficulty history");
        if (ancestor.height() != parent.height() - distance)
            throw new SecurityException("Non-contiguous difficulty history");
        return ancestor;
    }

    private static long scaled(BigInteger target, long elapsed, long expected) {
        // arith_uint256 multiplication wraps before division in Core.
        BigInteger next = target.multiply(BigInteger.valueOf(elapsed)).and(MASK)
                .divide(BigInteger.valueOf(expected)).min(POW_LIMIT);
        return CompactTarget.encode(next);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
