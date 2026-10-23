package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded IN-MEMORY graph of UNVERIFIED candidate headers.
 * Work here is only claimed work derived from nBits; it MUST NOT select an active chain.
 */
public final class RvnCandidateGraph {
    public record Entry(RvnHeaderCandidate candidate, long height, BigInteger claimedWork,
                        BigInteger claimedCumulativeWork) {
    }

    private final Map<RvnHash256, Entry> entries = new HashMap<>();
    private final int maxEntries;
    private final BigInteger powLimit;
    private final RvnHash256 anchorId;
    private final long anchorHeight;
    private final BigInteger anchorWork;

    public RvnCandidateGraph(int maxEntries, BigInteger powLimit, RvnHash256 anchorId,
                             long anchorHeight, BigInteger anchorWork) {
        if (maxEntries < 1 || maxEntries > 1_000_000 || powLimit == null || powLimit.signum() <= 0
                || powLimit.bitLength() > 256 || anchorId == null || anchorHeight < 0 || anchorWork == null
                || anchorWork.signum() < 0) throw new IllegalArgumentException("graph parameters");
        this.maxEntries = maxEntries;
        this.powLimit = powLimit;
        this.anchorId = anchorId;
        this.anchorHeight = anchorHeight;
        this.anchorWork = anchorWork;
    }

    public synchronized Entry add(RvnHeaderCandidate candidate) {
        Objects.requireNonNull(candidate);
        Entry existing = entries.get(candidate.id());
        if (existing != null) {
            if (!existing.candidate().equals(candidate)) throw new IllegalArgumentException("conflicting candidate id");
            return existing;
        }
        if (entries.size() >= maxEntries) throw new IllegalStateException("candidate graph full");
        if (candidate.id().equals(anchorId)) throw new IllegalArgumentException("anchor cannot be inserted");
        if (candidate.id().equals(candidate.parentId())) throw new IllegalArgumentException("self parent");
        Entry parent = entries.get(candidate.parentId());
        if (parent == null && !candidate.parentId().equals(anchorId))
            throw new IllegalArgumentException("orphan candidate");
        long height = parent == null ? Math.addExact(anchorHeight, 1) : Math.addExact(parent.height(), 1);
        if (candidate.kawpow() && candidate.declaredHeight() != height)
            throw new IllegalArgumentException("KawPoW declared height mismatch");
        BigInteger work = RvnBlockProof.fromBits(candidate.bits(), powLimit);
        BigInteger total = RvnBlockProof.accumulate(parent == null ? anchorWork : parent.claimedCumulativeWork(), work);
        Entry result = new Entry(candidate, height, work, total);
        entries.put(candidate.id(), result);
        return result;
    }

    public synchronized Optional<Entry> find(RvnHash256 id) {
        return Optional.ofNullable(entries.get(id));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Entry> pathToAnchor(RvnHash256 tip) {
        List<Entry> result = new ArrayList<>();
        Entry entry = entries.get(Objects.requireNonNull(tip));
        while (entry != null) {
            result.add(entry);
            entry = entries.get(entry.candidate().parentId());
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    /**
     * Ranking is diagnostic only; not a consensus or active-chain selection.
     */
    public synchronized List<Entry> byClaimedWork() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparing(Entry::claimedCumulativeWork).reversed()
                .thenComparing(e -> e.candidate().id()));
        return List.copyOf(list);
    }
}
