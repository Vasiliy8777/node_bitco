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
    static final int MAX_GETDATA_BATCH = 128;
    static final long REQUEST_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();

    private final LinkedHashMap<Hash256, LinkedHashMap<Peer, Announcement>> candidates = new LinkedHashMap<>();
    private final Map<Hash256, InFlight> inFlight = new HashMap<>();

    record Scheduled(Peer peer, InventoryVector vector) { }
    private record Announcement(long type) { }
    private record InFlight(Peer peer, long expires) { }

    void announced(Peer peer, InventoryVector vector) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(vector, "vector");
        if (vector.type() != InventoryVector.MSG_TX && vector.type() != 5) return;
        candidates.computeIfAbsent(vector.hash(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(peer, new Announcement(vector.type()));
    }

    List<Scheduled> schedule(long now, Predicate<Peer> eligiblePeer) {
        Objects.requireNonNull(eligiblePeer, "eligiblePeer");
        expire(now, eligiblePeer);
        if (inFlight.size() >= MAX_IN_FLIGHT) return List.of();

        List<Scheduled> result = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            if (inFlight.size() >= MAX_IN_FLIGHT) break;
            Hash256 hash = entry.getKey();
            if (inFlight.containsKey(hash)) continue;

            Peer selectedPeer = null;
            Announcement selected = null;
            for (var candidate : entry.getValue().entrySet()) {
                if (eligiblePeer.test(candidate.getKey())) {
                    selectedPeer = candidate.getKey();
                    selected = candidate.getValue();
                    break;
                }
            }
            if (selectedPeer == null) continue;

            long wireType = selected.type() == 5 ? 5 : InventoryVector.MSG_WITNESS_TX;
            inFlight.put(hash, new InFlight(selectedPeer, now + REQUEST_TIMEOUT_NANOS));
            result.add(new Scheduled(selectedPeer, new InventoryVector(wireType, hash)));
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
            candidates.remove(hash);
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
            if (request.expires() <= now || !eligiblePeer.test(request.peer())) {
                iterator.remove();
                removeCandidate(request.peer(), entry.getKey());
            }
        }

        var candidateIterator = candidates.entrySet().iterator();
        while (candidateIterator.hasNext()) {
            var entry = candidateIterator.next();
            entry.getValue().entrySet().removeIf(candidate -> !eligiblePeer.test(candidate.getKey()));
            if (entry.getValue().isEmpty() && !inFlight.containsKey(entry.getKey())) candidateIterator.remove();
        }
    }

    private void removeCandidate(Peer peer, Hash256 hash) {
        LinkedHashMap<Peer, Announcement> byPeer = candidates.get(hash);
        if (byPeer == null) return;
        byPeer.remove(peer);
        if (byPeer.isEmpty() && !inFlight.containsKey(hash)) candidates.remove(hash);
    }
}
