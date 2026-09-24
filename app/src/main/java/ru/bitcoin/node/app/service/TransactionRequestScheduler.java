package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.InventoryVector;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Tracks transaction announcements and schedules bounded downloads.
 *
 * One transaction hash can have candidates from several peers, but at most one
 * request for that hash is in flight. Timed-out/NOTFOUND requests can therefore
 * fail over to another announcing peer instead of losing the announcement.
 */
final class TransactionRequestScheduler {
    static final int MAX_IN_FLIGHT = 1024;
    static final int MAX_IN_FLIGHT_PER_PEER = 128;
    static final int MAX_CANDIDATES = 16_384;
    static final int MAX_CANDIDATES_PER_PEER = 2_048;
    static final int MAX_SOURCES_PER_HASH = 4;
    static final long ANNOUNCEMENT_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    static final int MAX_GETDATA_BATCH = 128;
    static final long REQUEST_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();

    private final LinkedHashMap<Hash256, LinkedHashMap<Peer, Announcement>> candidates = new LinkedHashMap<>();
    private final Map<Hash256, InFlight> inFlight = new HashMap<>();
    // Iteration order is the persistent round-robin order, including across refills.
    private final LinkedHashMap<Peer, Integer> candidateCounts = new LinkedHashMap<>();

    record Scheduled(Peer peer, InventoryVector vector) { }
    private record Announcement(long type, long receivedAt) { }
    private record InFlight(Peer peer, long expires) { }

    void announced(Peer peer, InventoryVector vector, long now) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(vector, "vector");
        if (vector.type() != InventoryVector.MSG_TX && vector.type() != 5) return;
        var sources = candidates.get(vector.hash());
        if (sources != null && (sources.containsKey(peer) || sources.size() >= MAX_SOURCES_PER_HASH)) return;
        if (candidateCounts.getOrDefault(peer, 0) >= MAX_CANDIDATES_PER_PEER) return;
        if (sources == null && candidates.size() >= MAX_CANDIDATES) return;
        candidates.computeIfAbsent(vector.hash(), ignored -> new LinkedHashMap<>())
                .put(peer, new Announcement(vector.type(), now));
        candidateCounts.merge(peer, 1, Integer::sum);
    }

    List<Scheduled> schedule(long now, Predicate<Peer> eligiblePeer) {
        Objects.requireNonNull(eligiblePeer, "eligiblePeer");
        expire(now, eligiblePeer);
        if (inFlight.size() >= MAX_IN_FLIGHT) return List.of();

        List<Scheduled> result = new ArrayList<>();
        Map<Peer, Integer> requestsPerPeer = new HashMap<>();
        inFlight.values().forEach(request -> requestsPerPeer.merge(request.peer(), 1, Integer::sum));
        Map<Peer, ArrayDeque<Scheduled>> pending = new HashMap<>();
        for (var entry : candidates.entrySet()) {
            Hash256 hash = entry.getKey();
            if (inFlight.containsKey(hash)) continue;
            for (var candidate : entry.getValue().entrySet()) {
                if (requestsPerPeer.getOrDefault(candidate.getKey(), 0) < MAX_IN_FLIGHT_PER_PEER
                        && eligiblePeer.test(candidate.getKey())) {
                    long wireType = candidate.getValue().type() == 5 ? 5 : InventoryVector.MSG_WITNESS_TX;
                    pending.computeIfAbsent(candidate.getKey(), ignored -> new ArrayDeque<>())
                            .addLast(new Scheduled(candidate.getKey(), new InventoryVector(wireType, hash)));
                }
            }
        }
        var turns = new ArrayDeque<>(candidateCounts.keySet());
        while (!turns.isEmpty() && inFlight.size() < MAX_IN_FLIGHT) {
            Peer peer = turns.removeFirst();
            var queue = pending.get(peer);
            if (queue == null || requestsPerPeer.getOrDefault(peer, 0) >= MAX_IN_FLIGHT_PER_PEER) continue;
            while (!queue.isEmpty() && inFlight.containsKey(queue.getFirst().vector().hash())) queue.removeFirst();
            if (queue.isEmpty()) continue;
            Scheduled request = queue.removeFirst();
            inFlight.put(request.vector().hash(), new InFlight(peer, now + REQUEST_TIMEOUT_NANOS));
            requestsPerPeer.merge(peer, 1, Integer::sum);
            result.add(request);
            int count = candidateCounts.remove(peer);
            candidateCounts.put(peer, count);
            turns.addLast(peer);
        }
        return List.copyOf(result);
    }

    void notFound(Peer peer, Hash256 hash) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(hash, "hash");
        InFlight request = inFlight.get(hash);
        if (request != null && request.peer() == peer) inFlight.remove(hash);
        removeCandidate(peer, hash);
    }

    boolean isExpected(Peer peer, Hash256... hashes) {
        for (Hash256 hash : hashes) {
            InFlight request = inFlight.get(hash);
            if (request != null && request.peer() == peer) return true;
        }
        return false;
    }

    void forget(Hash256... hashes) {
        for (Hash256 hash : hashes) {
            inFlight.remove(hash);
            var removed = candidates.remove(hash);
            if (removed != null) removed.keySet().forEach(this::decrementCandidateCount);
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    int candidateCount() {
        return candidates.size();
    }

    private void expire(long now, Predicate<Peer> eligiblePeer) {
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            InFlight request = entry.getValue();
            if (now - request.expires() >= 0 || !eligiblePeer.test(request.peer())) {
                iterator.remove();
                removeCandidate(request.peer(), entry.getKey());
            }
        }

        var candidateIterator = candidates.entrySet().iterator();
        while (candidateIterator.hasNext()) {
            var entry = candidateIterator.next();
            entry.getValue().entrySet().removeIf(candidate -> {
                InFlight request = inFlight.get(entry.getKey());
                boolean requested = request != null && request.peer() == candidate.getKey();
                if (!eligiblePeer.test(candidate.getKey()) || (!requested
                        && now - candidate.getValue().receivedAt() >= ANNOUNCEMENT_TTL_NANOS)) {
                    decrementCandidateCount(candidate.getKey());
                    return true;
                }
                return false;
            });
            if (entry.getValue().isEmpty() && !inFlight.containsKey(entry.getKey())) candidateIterator.remove();
        }
    }

    private void removeCandidate(Peer peer, Hash256 hash) {
        LinkedHashMap<Peer, Announcement> byPeer = candidates.get(hash);
        if (byPeer == null) return;
        if (byPeer.remove(peer) != null) decrementCandidateCount(peer);
        if (byPeer.isEmpty() && !inFlight.containsKey(hash)) candidates.remove(hash);
    }

    private void decrementCandidateCount(Peer peer) {
        candidateCounts.computeIfPresent(peer, (ignored, count) -> count == 1 ? null : count - 1);
    }
}
