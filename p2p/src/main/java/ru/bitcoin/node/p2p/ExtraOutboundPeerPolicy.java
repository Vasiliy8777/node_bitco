package ru.bitcoin.node.p2p;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/** Bitcoin Core 32.1 stale-tip and extra full-relay eviction decisions. */
public final class ExtraOutboundPeerPolicy {
    private static final java.security.SecureRandom PROBE_RANDOM = new java.security.SecureRandom();
    public static final long CHECK_INTERVAL_SECONDS = 45;
    public static final long STALE_CHECK_INTERVAL_SECONDS = 600;
    private final long staleAfterSeconds;
    private long nextStaleCheck;
    private long firstCheck = -1;
    private boolean tryExtra;

    public ExtraOutboundPeerPolicy(long targetSpacingSeconds) {
        if (targetSpacingSeconds <= 0) throw new IllegalArgumentException("Target spacing must be positive");
        staleAfterSeconds = Math.multiplyExact(3, targetSpacingSeconds);
    }

    public boolean check(long now, long lastTipUpdate, boolean blocksInFlight, boolean enabled) {
        if (firstCheck < 0) firstCheck = now;
        if (now > nextStaleCheck) {
            long lastUpdate = lastTipUpdate == 0 ? firstCheck : lastTipUpdate;
            tryExtra = enabled && !blocksInFlight && lastUpdate < now - staleAfterSeconds;
            nextStaleCheck = now + STALE_CHECK_INTERVAL_SECONDS;
        }
        return tryExtra;
    }

    public void evicted() { tryExtra = false; }

    public record Candidate(long id, int network, long connectedAtMicros, long lastNewBlockAnnouncement,
                            boolean protectedPeer, boolean blocksInFlight) { }

    public static OptionalLong evictionCandidate(List<Candidate> peers, long nowMicros) {
        var worst = peers.stream().filter(peer -> !peer.protectedPeer())
                .filter(peer -> peers.stream().filter(other -> other.network() == peer.network()).count() > 1)
                .min(Comparator.comparingLong(Candidate::lastNewBlockAnnouncement)
                        .thenComparing(Comparator.comparingLong(Candidate::id).reversed()));
        // Core selects the worst peer first: it doesn't evict a different peer
        // when that candidate is young or currently downloading a block.
        return worst.filter(peer -> nowMicros - peer.connectedAtMicros() > 30_000_000 && !peer.blocksInFlight())
                .map(peer -> OptionalLong.of(peer.id())).orElseGet(OptionalLong::empty);
    }

    public record BlockCandidate(long id, long connectedAtMicros, long lastBlock, boolean blocksInFlight) { }

    public static OptionalLong blockEvictionCandidate(List<BlockCandidate> peers, long nowMicros) {
        var youngest = peers.stream().sorted(Comparator.comparingLong(BlockCandidate::id).reversed())
                .limit(2).toList();
        if (youngest.size() < 2) return OptionalLong.empty();
        var victim = youngest.get(0).lastBlock() > youngest.get(1).lastBlock() ? youngest.get(1) : youngest.get(0);
        return nowMicros - victim.connectedAtMicros() >= 30_000_000 && !victim.blocksInFlight()
                ? OptionalLong.of(victim.id()) : OptionalLong.empty();
    }

    static long randomProbeDelayMicros() { return probeDelayMicros(PROBE_RANDOM.nextDouble()); }

    static long probeDelayMicros(double uniform) {
        if (!(uniform >= 0 && uniform < 1)) throw new IllegalArgumentException("Random sample must be in [0,1)");
        return (long) Math.floor(-StrictMath.log1p(-uniform) * 300_000_000 + 0.5);
    }
}
