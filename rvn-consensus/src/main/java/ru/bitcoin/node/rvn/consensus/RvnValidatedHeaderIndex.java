package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.*;

/**
 * Fail-closed, bounded validated-header graph. Only accepts a header after an
 * externally supplied COMPLETE Ravencoin consensus verifier approves it.
 * The caller MUST provide a genuinely verified anchor and a real verifier.
 */
public final class RvnValidatedHeaderIndex {
    public record Entry(RvnHeaderCandidate candidate, long height,
                        BigInteger blockWork, BigInteger chainWork) {
    }

    private final Map<RvnHash256, Entry> entries = new HashMap<>();
    private final Map<RvnHash256, byte[]> verifiedWire = new HashMap<>();
    private final int capacity;
    private final RvnHash256 anchor;
    private final long anchorHeight;
    private final BigInteger anchorWork, powLimit;
    private RvnHash256 bestId;
    private BigInteger bestWork;
    private long bestHeight;

    public RvnValidatedHeaderIndex(int capacity, RvnHash256 anchor, long anchorHeight,
                                   BigInteger anchorWork, BigInteger powLimit) {
        if (capacity < 1 || capacity > 1_000_000 || anchor == null || anchorHeight < 0
                || anchorWork == null || anchorWork.signum() < 0 || powLimit == null
                || powLimit.signum() <= 0 || powLimit.bitLength() > 256)
            throw new IllegalArgumentException("Invalid index configuration");
        this.capacity = capacity;
        this.anchor = anchor;
        this.anchorHeight = anchorHeight;
        this.anchorWork = anchorWork;
        this.powLimit = powLimit;
        this.bestId = anchor;
        this.bestWork = anchorWork;
        this.bestHeight = anchorHeight;
    }

    public synchronized Entry admit(RvnHeaderCandidate candidate, byte[] raw,
                                    RvnHeaderProofGate.Verifier verifier) {
        Objects.requireNonNull(candidate);
        new RvnHeaderWireFields(raw).requireMatches(candidate);
        Entry existing = entries.get(candidate.id());
        if (existing != null) {
            if (!existing.candidate().equals(candidate) || !Arrays.equals(verifiedWire.get(candidate.id()), raw))
                throw new SecurityException("Conflicting metadata for block ID");
            // No need to invoke verifier again for previously validated identical candidate.
            return existing;
        }
        if (candidate.id().equals(anchor) || candidate.id().equals(candidate.parentId()))
            throw new SecurityException("Invalid candidate ID or self-parent");
        Entry parent = entries.get(candidate.parentId());
        if (parent == null && !candidate.parentId().equals(anchor))
            throw new IllegalArgumentException("Missing validated parent");
        long height = Math.addExact(parent == null ? anchorHeight : parent.height(), 1);
        if (candidate.kawpow() && candidate.declaredHeight() != height)
            throw new SecurityException("Declared height differs from parent height");
        BigInteger work = RvnBlockProof.fromBits(candidate.bits(), powLimit);
        BigInteger cumulative = RvnBlockProof.accumulate(
                parent == null ? anchorWork : parent.chainWork(), work);
        if (entries.size() >= capacity) throw new IllegalStateException("Validated index full");
        // The verifier MUST check algorithm, expected nBits, timestamps, deployments,
        // and block ID. A stub that returns true is UNSAFE outside tests.
        RvnHeaderProofGate.requireVerified(candidate, raw, verifier);
        Entry added = new Entry(candidate, height, work, cumulative);
        entries.put(candidate.id(), added);
        verifiedWire.put(candidate.id(), raw.clone());
        if (cumulative.compareTo(bestWork) > 0 ||
                (cumulative.equals(bestWork) && candidate.id().compareTo(bestId) < 0)) {
            bestId = candidate.id();
            bestWork = cumulative;
            bestHeight = height;
        }
        return added;
    }

    /**
     * Returns a defensive copy of the exact header bytes originally verified.
     */
    public synchronized Optional<byte[]> verifiedRaw(RvnHash256 id) {
        byte[] raw = verifiedWire.get(Objects.requireNonNull(id));
        return raw == null ? Optional.empty() : Optional.of(raw.clone());
    }

    public synchronized Optional<Entry> find(RvnHash256 id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id)));
    }

    public synchronized RvnHash256 bestId() {
        return bestId;
    }

    public synchronized long bestHeight() {
        return bestHeight;
    }

    public synchronized BigInteger bestWork() {
        return bestWork;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Entry> pathToAnchor(RvnHash256 tip) {
        Objects.requireNonNull(tip);
        if (tip.equals(anchor)) return List.of();
        Entry node = entries.get(tip);
        if (node == null) throw new IllegalArgumentException("Unknown tip");
        List<Entry> path = new ArrayList<>();
        while (node != null) {
            path.add(node);
            node = entries.get(node.candidate().parentId());
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    public synchronized RvnHash256 commonAncestor(RvnHash256 first, RvnHash256 second) {
        Set<RvnHash256> ancestors = new HashSet<>();
        RvnHash256 id = Objects.requireNonNull(first);
        while (true) {
            ancestors.add(id);
            if (id.equals(anchor)) break;
            Entry entry = entries.get(id);
            if (entry == null) throw new IllegalArgumentException("Unknown first tip");
            id = entry.candidate().parentId();
        }
        id = Objects.requireNonNull(second);
        while (!ancestors.contains(id)) {
            Entry entry = entries.get(id);
            if (entry == null) throw new IllegalArgumentException("Unknown second tip");
            id = entry.candidate().parentId();
        }
        return id;
    }
}
