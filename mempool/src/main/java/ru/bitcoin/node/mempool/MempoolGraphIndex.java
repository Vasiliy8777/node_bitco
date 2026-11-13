package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import java.util.*;

/** Persistent main-pool adjacency/components, owned by the Mempool monitor. No transaction references. */
final class MempoolGraphIndex {
    private record Node(Set<Hash256> parents, Set<Hash256> children) {
        Node { parents = Set.copyOf(parents); children = Set.copyOf(children); }
    }
    private static final class Component {
        final Set<Hash256> members;
        Component(Collection<Hash256> members) { this.members = Set.copyOf(members); }
        Set<Hash256> snapshot() { return members; }
    }
    private Map<Hash256, Node> nodes = new LinkedHashMap<>();
    private Map<Hash256, Component> membership = new HashMap<>();
    private Set<Component> components = new LinkedHashSet<>();
    private Map<Component, ClusterOptimization> optimizations = new HashMap<>();
    private List<Set<Hash256>> published;
    private long revision;
    private final Set<Component> needsWork = new LinkedHashSet<>();
    private final Set<Component> needsFix = new LinkedHashSet<>();
    private final Set<Component> acceptable = new LinkedHashSet<>();
    // Core's outer FastRandomContext is cryptographic and shared by main/staging.
    // Only the seeded inner SFL context uses the deliberately insecure xoroshiro.
    private java.security.SecureRandom rng = new java.security.SecureRandom();
    private List<List<ClusterLinearization.Chunk>> linearizationView;

    long revision() { return revision; }
    long memoryUsage(Map<Hash256, MempoolEntry> entries) {
        long total = 0;
        for (var component : components) {
            boolean dirty = needsWork.contains(component) || needsFix.contains(component);
            int chunks = component.members.size() == 1 ? 1 : dirty ? 0 : optimizations.get(component).chunks.size();
            total = Math.addExact(total,CoreMemoryUsage.component(component.members.size(),chunks));
        }
        return total;
    }
    void scoresChanged() { revision++; linearizationView = null; }
    void entryChanged(Hash256 id) {
        var component = membership.get(id);
        if (component != null) invalidate(component, optimizations.get(component).order);
        scoresChanged();
    }

    /** Fork by sharing immutable nodes, components and optimization snapshots.
     * Every mutation replaces affected objects; map/queue ownership remains independent.
     */
    void copyFrom(MempoolGraphIndex source) {
        var copiedNodes = new LinkedHashMap<>(source.nodes);
        var copiedMembership = new HashMap<>(source.membership);
        var copiedComponents = new LinkedHashSet<>(source.components);
        var copiedOptimizations = new HashMap<>(source.optimizations);
        var copiedNeedsWork = new LinkedHashSet<>(source.needsWork);
        var copiedNeedsFix = new LinkedHashSet<>(source.needsFix);
        var copiedAcceptable = new LinkedHashSet<>(source.acceptable);
        var copiedRng = source.rng;
        var snapshot = source.published;
        var chunkSnapshot = source.linearizationView;
        nodes = copiedNodes; membership = copiedMembership; components = copiedComponents;
        optimizations = copiedOptimizations;
        needsWork.clear(); needsWork.addAll(copiedNeedsWork); acceptable.clear(); acceptable.addAll(copiedAcceptable);
        needsFix.clear(); needsFix.addAll(copiedNeedsFix);
        rng = copiedRng;
        changed(); // Advance our own revision, including when replacing a previously used graph.
        published = snapshot;
        linearizationView = chunkSnapshot;
    }

    void add(Hash256 id, Collection<Hash256> parentIds, Collection<Hash256> childIds) {
        Objects.requireNonNull(id);
        if (nodes.containsKey(id)) throw new IllegalArgumentException("Duplicate graph node");
        var parents = new LinkedHashSet<>(parentIds);
        var children = new LinkedHashSet<>(childIds);
        var adjacent = new LinkedHashSet<Component>();
        for (var neighbor : parents) adjacent.add(requireComponent(neighbor));
        for (var neighbor : children) adjacent.add(requireComponent(neighbor));
        var oldOrder = new ArrayList<Hash256>();
        var mergedMembers = new LinkedHashSet<Hash256>(); mergedMembers.add(id);
        for (var component : adjacent) {
            oldOrder.addAll(optimizations.get(component).order); mergedMembers.addAll(component.members);
        }
        nodes.put(id, new Node(Set.of(), Set.of()));
        for (var parent : parents) addLink(parent, id);
        for (var child : children) addLink(id, child);
        var merged = new Component(mergedMembers);
        for (var member : merged.members) membership.put(member, merged);
        for (var component : adjacent) forget(component);
        components.add(merged);
        invalidate(merged, oldOrder);
        changed();
    }

    Set<Hash256> descendants(Hash256 root) {
        return descendants(List.of(root));
    }

    Set<Hash256> descendants(Collection<Hash256> roots) { return reachable(roots, false); }
    Set<Hash256> ancestors(Hash256 root) { return reachable(List.of(root), true); }
    Set<Hash256> parents(Hash256 id) {
        var node = nodes.get(id); return node == null ? Set.of() : Set.copyOf(node.parents);
    }
    Set<Hash256> children(Hash256 id) {
        var node = nodes.get(id); return node == null ? Set.of() : Set.copyOf(node.children);
    }
    Set<Hash256> component(Hash256 id) {
        var component = membership.get(id); return component == null ? Set.of() : component.snapshot();
    }

    /** Include known roots; unknown roots have no vertices in this graph. */
    private Set<Hash256> reachable(Collection<Hash256> roots, boolean upstream) {
        var found = new LinkedHashSet<Hash256>();
        var queue = new ArrayDeque<Hash256>();
        for (var root : roots) if (nodes.containsKey(root) && found.add(root)) queue.add(root);
        while (!queue.isEmpty()) {
            var node = nodes.get(queue.remove());
            for (var next : upstream ? node.parents : node.children)
                if (found.add(next)) queue.add(next);
        }
        return Set.copyOf(found);
    }

    void removeAll(Collection<Hash256> ids) {
        var affected = new LinkedHashSet<Component>();
        boolean removed = false;
        for (var id : new LinkedHashSet<>(ids)) {
            var node = nodes.remove(id);
            if (node == null) continue;
            removed = true;
            var component = membership.remove(id); affected.add(component);
            for (var parent : node.parents) {
                var retained = nodes.get(parent);
                if (retained != null) nodes.put(parent, new Node(retained.parents, without(retained.children, id)));
            }
            for (var child : node.children) {
                var retained = nodes.get(child);
                if (retained != null) nodes.put(child, new Node(without(retained.parents, id), retained.children));
            }
        }
        if (!removed) return;
        for (var component : affected) {
            var oldOrder = optimizations.get(component).order;
            forget(component);
            addComponents(component.members.stream().filter(nodes::containsKey).toList(), oldOrder);
        }
        changed();
    }

    /** Full reconstruction, also used as a reference for incremental reconciliation tests. */
    void rebuild(Map<Hash256, MempoolEntry> entries) {
        var replacement = new MempoolGraphIndex();
        for (var id : entries.keySet()) replacement.nodes.put(id, new Node(Set.of(), Set.of()));
        for (var item : entries.entrySet()) {
            for (var input : item.getValue().transaction().inputs()) {
                var parent = input.previousOutput().transactionId();
                if (replacement.nodes.containsKey(parent)) replacement.addLink(parent, item.getKey());
            }
        }
        replacement.addComponents(replacement.nodes.keySet(), List.of());
        nodes = replacement.nodes; membership = replacement.membership; components = replacement.components;
        optimizations = replacement.optimizations;
        needsWork.clear(); needsWork.addAll(replacement.needsWork); acceptable.clear();
        needsFix.clear(); needsFix.addAll(replacement.needsFix);
        changed();
    }

    /** Apply a committed pool, retaining unchanged adjacency and component snapshots.
     * spent must describe the final pool. New vertices may arrive in any order.
     * txid commits to inputs; existing vertices therefore need no input scan.
     */
    void reconcile(Map<Hash256, MempoolEntry> entries, Map<OutPoint, Hash256> spent) {
        var removed = new HashSet<>(nodes.keySet());
        removed.removeAll(entries.keySet());
        removeAll(removed);
        for (var item : entries.entrySet()) {
            var id = item.getKey();
            if (nodes.containsKey(id)) continue;
            var transaction = item.getValue().transaction();
            var parents = new LinkedHashSet<Hash256>();
            var children = new LinkedHashSet<Hash256>();
            for (var input : transaction.inputs()) {
                var parent = input.previousOutput().transactionId();
                if (nodes.containsKey(parent)) parents.add(parent);
            }
            for (int output = 0; output < transaction.outputs().size(); output++) {
                var child = spent.get(new OutPoint(id, new UInt32(output)));
                if (nodes.containsKey(child)) children.add(child);
            }
            add(id, parents, children);
        }
        // A bulk commit may change fees or sigops without changing txids.
        scoresChanged();
    }

    List<Set<Hash256>> components() {
        if (published == null) published = components.stream().map(Component::snapshot).toList();
        return published;
    }

    private Component requireComponent(Hash256 id) {
        var component = membership.get(id);
        if (component == null) throw new IllegalArgumentException("Missing graph neighbor");
        return component;
    }
    private void addComponents(Collection<Hash256> candidates, List<Hash256> oldOrder) {
        var remaining = new LinkedHashSet<>(candidates);
        while (!remaining.isEmpty()) {
            var members = new LinkedHashSet<Hash256>();
            var queue = new ArrayDeque<Hash256>();
            var root = remaining.iterator().next(); remaining.remove(root); queue.add(root);
            while (!queue.isEmpty()) {
                var id = queue.remove(); members.add(id);
                var node = nodes.get(id);
                for (var parent : node.parents) if (remaining.remove(parent)) queue.add(parent);
                for (var child : node.children) if (remaining.remove(child)) queue.add(child);
            }
            var component = new Component(members); components.add(component);
            for (var id : members) membership.put(id, component);
            invalidate(component, oldOrder.stream().filter(component.members::contains).toList());
        }
    }
    private void invalidate(Component component, Collection<Hash256> oldOrder) {
        var order = new LinkedHashSet<Hash256>();
        for (var id : oldOrder) if (component.members.contains(id)) order.add(id);
        component.members.stream().sorted(Hash256::compareRawBytes).forEach(order::add);
        var done = new HashSet<Hash256>(); boolean fix = false;
        for (var id : order) { if (!done.containsAll(nodes.get(id).parents)) fix = true; done.add(id); }
        optimizations.put(component, ClusterOptimization.pending(oldOrder, fix)); acceptable.remove(component);
        needsWork.remove(component); needsFix.remove(component);
        // Core represents singleton clusters as already optimal, with zero work.
        if (component.members.size() > 1) (fix ? needsFix : needsWork).add(component);
        linearizationView = null;
    }
    boolean doWork(Map<Hash256, MempoolEntry> entries, long budget) {
        if (budget < 0) throw new IllegalArgumentException("Negative graph work budget");
        long spent = 0;
        for (var queue : List.of(needsFix, needsWork, acceptable)) {
            while (!queue.isEmpty()) {
                if (spent >= budget) return false;
                int position = rng.nextInt(queue.size());
                var iterator = queue.iterator(); Component component = iterator.next();
                while (position-- > 0) component = iterator.next();
                var previous = optimizations.get(component);
                long available = budget - spent;
                if (queue != acceptable) available = Math.min(available, ClusterOptimization.ACCEPTABLE_COST);
                var result = previous.run(entries, component.members, available, rng.nextLong());
                optimizations.put(component, result); scoresChanged();
                spent = Math.addExact(spent, result.cost);
                queue.remove(component);
                if (result.quality == ClusterOptimization.Quality.ACCEPTABLE) acceptable.add(component);
                else if (result.quality == ClusterOptimization.Quality.NEEDS_RELINEARIZE) needsWork.add(component);
                if (result.quality == previous.quality) return false;
            }
        }
        return true;
    }
    boolean optimal() { return needsFix.isEmpty() && needsWork.isEmpty() && acceptable.isEmpty(); }
    List<ClusterLinearization.Chunk> chunks(Map<Hash256, MempoolEntry> entries, Hash256 id) {
        var component = requireComponent(id);
        if (optimizations.get(component).chunks.isEmpty() || needsWork.contains(component) || needsFix.contains(component)) {
            var result = optimizations.get(component).run(entries, component.members, ClusterOptimization.ACCEPTABLE_COST,
                    component.members.size() == 1 ? 0 : rng.nextLong());
            optimizations.put(component, result); needsWork.remove(component);
            needsFix.remove(component);
            if (result.quality != ClusterOptimization.Quality.OPTIMAL) acceptable.add(component);
            scoresChanged();
        }
        return optimizations.get(component).chunks;
    }
    List<List<ClusterLinearization.Chunk>> linearizations(Map<Hash256, MempoolEntry> entries) {
        if (linearizationView == null) {
            var result = new ArrayList<List<ClusterLinearization.Chunk>>();
            for (var component : components) result.add(chunks(entries, component.members.iterator().next()));
            linearizationView = List.copyOf(result);
        }
        return linearizationView;
    }
    private void changed() { revision++; published = null; linearizationView = null; }
    private void forget(Component component) {
        components.remove(component); optimizations.remove(component); needsWork.remove(component); needsFix.remove(component); acceptable.remove(component);
    }
    private void addLink(Hash256 parent, Hash256 child) {
        var parentNode = nodes.get(parent);
        var childNode = nodes.get(child);
        var children = new LinkedHashSet<>(parentNode.children); children.add(child);
        var parents = new LinkedHashSet<>(childNode.parents); parents.add(parent);
        nodes.put(parent, new Node(parentNode.parents, children));
        // A self-edge is accepted only by the reconstruction test helper, not admission.
        if (parent.equals(child)) nodes.put(child, new Node(parents, children));
        else nodes.put(child, new Node(parents, childNode.children));
    }
    private static Set<Hash256> without(Set<Hash256> original, Hash256 id) {
        var result = new LinkedHashSet<>(original); result.remove(id); return result;
    }
}
