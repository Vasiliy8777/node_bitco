package ru.bitcoin.node.rvn.consensus;
import java.util.*;
/** Bounded staging of UNVERIFIED orphan headers. Not persisted or treated as valid. */
public final class RvnHeaderOrphanBuffer {
    public record Pending(RvnHeaderCandidate candidate, byte[] raw) {
        public Pending {
            Objects.requireNonNull(candidate);
            if (raw == null || raw.length != (candidate.kawpow() ? 120 : 80))
                throw new IllegalArgumentException("raw");
            raw = raw.clone();
        }
        @Override public byte[] raw() { return raw.clone(); }
    }
    private final int capacity;
    private final LinkedHashMap<RvnHash256, Pending> pending = new LinkedHashMap<>();
    public RvnHeaderOrphanBuffer(int capacity) {
        if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized boolean add(Pending orphan) {
        Objects.requireNonNull(orphan);
        new RvnHeaderWireFields(orphan.raw()).requireMatches(orphan.candidate());
        if (pending.containsKey(orphan.candidate().id())) {
            Pending old = pending.get(orphan.candidate().id());
            if (!old.candidate().equals(orphan.candidate()) || !Arrays.equals(old.raw(), orphan.raw()))
                throw new SecurityException("Conflicting orphan ID");
            return false;
        }
        if (pending.size() == capacity) {
            Iterator<RvnHash256> it = pending.keySet().iterator();
            it.next(); it.remove();
        }
        pending.put(orphan.candidate().id(), orphan);
        return true;
    }
    public synchronized List<Pending> drainChildren(RvnHash256 parentId) {
        Objects.requireNonNull(parentId);
        List<Pending> result = new ArrayList<>();
        Iterator<Map.Entry<RvnHash256, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending p = it.next().getValue();
            if (p.candidate().parentId().equals(parentId)) {
                result.add(p); it.remove();
            }
        }
        return List.copyOf(result);
    }
    public synchronized int size() { return pending.size(); }
}
