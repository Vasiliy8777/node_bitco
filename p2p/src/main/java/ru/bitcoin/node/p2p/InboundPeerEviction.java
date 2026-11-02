package ru.bitcoin.node.p2p;

import java.util.*;
import java.util.function.Predicate;

/**
 * Protection and selection adapted from Bitcoin Core v32.1 node/eviction.cpp.
 * Copyright the Bitcoin Core developers; MIT license in META-INF/licenses/bitcoin-core-MIT.txt.
 * Comparators intentionally have the same equivalence classes as Core; Java's stable
 * sort supplies deterministic choices where C++ leaves equal elements unspecified.
 */
public final class InboundPeerEviction {
    private InboundPeerEviction() { }
    public enum Network { IPV4, IPV6, ONION, I2P, CJDNS, OTHER }
    public record Candidate(long id, long connected, long minPingNanos, long lastBlock, long lastTransaction,
                            boolean relevantServices, boolean relaysTransactions, boolean bloomFilter,
                            long keyedNetGroup, boolean preferEvict, boolean local, Network network,
                            boolean noBan, boolean inbound) {
        public Candidate { Objects.requireNonNull(network); }
    }

    private static final Comparator<Candidate> YOUNGEST = Comparator.comparingLong(Candidate::connected).reversed();
    private static final Comparator<Candidate> BLOCK_TIME = Comparator.comparingLong(Candidate::lastBlock)
            .thenComparing(Candidate::relevantServices).thenComparing(YOUNGEST);
    private static final Comparator<Candidate> TX_TIME = Comparator.comparingLong(Candidate::lastTransaction)
            .thenComparing(Candidate::relaysTransactions)
            .thenComparing(Candidate::bloomFilter, Comparator.reverseOrder()).thenComparing(YOUNGEST);

    public static OptionalLong select(List<Candidate> supplied) {
        var candidates = new ArrayList<>(supplied);
        candidates.removeIf(candidate -> candidate.noBan() || !candidate.inbound());
        eraseLast(candidates, (a, b) -> Long.compareUnsigned(a.keyedNetGroup(), b.keyedNetGroup()), 4, ignored -> true);
        eraseLast(candidates, Comparator.comparingLong(Candidate::minPingNanos).reversed(), 8, ignored -> true);
        eraseLast(candidates, TX_TIME, 4, ignored -> true);
        eraseLast(candidates, Comparator.comparing(Candidate::relaysTransactions, Comparator.reverseOrder())
                .thenComparing(BLOCK_TIME), 8, candidate -> !candidate.relaysTransactions() && candidate.relevantServices());
        eraseLast(candidates, BLOCK_TIME, 4, ignored -> true);
        protectByRatio(candidates);
        if (candidates.isEmpty()) return OptionalLong.empty();
        if (candidates.stream().anyMatch(Candidate::preferEvict)) candidates.removeIf(candidate -> !candidate.preferEvict());

        // The ratio phase leaves candidates in descending connection-time order.
        var groups = new HashMap<Long, List<Candidate>>();
        List<Candidate> largest = null;
        for (var candidate : candidates) {
            var group = groups.computeIfAbsent(candidate.keyedNetGroup(), ignored -> new ArrayList<>());
            group.add(candidate);
            if (largest == null || group.size() > largest.size()
                    || (group.size() == largest.size() && group.getFirst().connected() > largest.getFirst().connected()))
                largest = group;
        }
        return OptionalLong.of(Objects.requireNonNull(largest).getFirst().id());
    }

    /** Mutates candidates to remove Core's age/privacy-network protection set. */
    public static void protectByRatio(List<Candidate> candidates) {
        int total = candidates.size() / 2;
        int networkBudget = total / 2;
        record Category(boolean local, Network network, int count) { }
        var categories = new ArrayList<Category>();
        for (var network : List.of(Network.CJDNS, Network.I2P, Network.OTHER, Network.ONION)) {
            boolean local = network == Network.OTHER;
            int count = (int) candidates.stream().filter(candidate -> local ? candidate.local() : candidate.network() == network).count();
            categories.add(new Category(local, network, count));
        }
        categories.sort(Comparator.comparingInt(Category::count));
        int protectedCount = 0;
        while (protectedCount < networkBudget) {
            long networks = categories.stream().filter(category -> category.count() > 0).count();
            if (networks == 0) break;
            int perNetwork = Math.max((networkBudget - protectedCount) / (int) networks, 1);
            boolean progressed = false;
            for (int index = 0; index < categories.size(); index++) {
                var category = categories.get(index);
                if (category.count() == 0) continue;
                Predicate<Candidate> matches = candidate -> category.local() ? candidate.local() : candidate.network() == category.network();
                Comparator<Candidate> order = Comparator.comparing(matches::test).thenComparing(YOUNGEST);
                int before = candidates.size();
                eraseLast(candidates, order, perNetwork, matches);
                int removed = before - candidates.size();
                if (removed > 0) {
                    progressed = true; protectedCount += removed;
                    if (protectedCount >= networkBudget) break;
                    categories.set(index, new Category(category.local(), category.network(), category.count() - removed));
                }
            }
            if (!progressed) break;
        }
        eraseLast(candidates, YOUNGEST, total - protectedCount, ignored -> true);
    }

    private static void eraseLast(List<Candidate> candidates, Comparator<Candidate> order, int count, Predicate<Candidate> predicate) {
        candidates.sort(order);
        int first = Math.max(0, candidates.size() - count);
        for (int index = candidates.size() - 1; index >= first; index--)
            if (predicate.test(candidates.get(index))) candidates.remove(index);
    }
}
