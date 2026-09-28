package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.consensus.transaction.UtxoView;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.*;

/** All entry and spent-outpoint mutations share this object's monitor.
 * Admission requires a stable external chain/UTXO snapshot; acquire the chain lock first.
 * Replacement and resource checks complete before committing a prospective pool.
 */
public final class Mempool {
    private final MempoolPolicy policy;
    private final MempoolLimits limits;
    private final java.time.Clock clock;
    private final RollingMinimumFee rollingFee = new RollingMinimumFee();
    private final Map<Hash256, MempoolEntry> entries = new LinkedHashMap<>();
    private final Map<OutPoint, Hash256> spent = new HashMap<>();
    private final Map<Hash256, Long> feeDeltas = new LinkedHashMap<>();
    private final Set<Hash256> unbroadcast = new LinkedHashSet<>();
    /** Monotonic transaction-set mutation sequence used by getrawmempool. */
    private long sequence;

    public Mempool() { this(new MempoolPolicy()); }
    public Mempool(MempoolPolicy policy) { this(policy, MempoolLimits.DEFAULT, java.time.Clock.systemUTC()); }
    public Mempool(MempoolPolicy policy, MempoolLimits limits, java.time.Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized MempoolEntry admit(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos) {
        return admitInternal(transaction, context, chainUtxos, false, false, null, null);
    }

    /** Revalidates a persisted transaction while preserving its original local admission time. */
    public synchronized MempoolEntry admitRestored(Transaction transaction, long arrivalTime,
                                                   MempoolValidationContext context, UtxoView chainUtxos) {
        return admitRestored(transaction, arrivalTime, Math.max(0L, context.nextBlockHeight() - 1L), false, context, chainUtxos);
    }

    public synchronized MempoolEntry admitRestored(Transaction transaction, long arrivalTime, long admissionHeight,
                                                   boolean wasUnbroadcast, MempoolValidationContext context, UtxoView chainUtxos) {
        if (arrivalTime < 0 || admissionHeight < 0) throw new IllegalArgumentException("negative persisted mempool metadata");
        MempoolEntry entry = admitInternal(transaction, context, chainUtxos, false, false, arrivalTime, admissionHeight);
        if (wasUnbroadcast) unbroadcast.add(transaction.txId());
        return entry;
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos, boolean packageMode) {
        return admitInternal(transaction, context, chainUtxos, packageMode, false, null, null);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos, boolean packageMode, boolean bypassLimits) {
        return admitInternal(transaction, context, chainUtxos, packageMode, bypassLimits, null, null);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos,
                                       boolean packageMode, boolean bypassLimits, Long restoredArrivalTime, Long restoredAdmissionHeight) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(chainUtxos, "chainUtxos");
        Hash256 txId = transaction.txId();
        if (entries.containsKey(txId)) throw new MempoolAdmissionException("Transaction already exists: " + txId);
        if (transaction.isCoinbase()) throw new MempoolAdmissionException("Coinbase cannot enter mempool");
        long weight = TransactionWeight.calculate(transaction);
        policy.validateStandardStructure(transaction, weight);
        Set<Hash256> conflicts = new HashSet<>();
        for (var input : transaction.inputs()) {
            Hash256 conflict = spent.get(input.previousOutput());
            if (conflict != null) conflicts.add(conflict);
        }
        if (!packageMode) MempoolGraphPolicy.addSiblingConflict(entries, transaction, conflicts);
        Set<Hash256> evicted = MempoolGraphPolicy.descendants(entries, conflicts);
        Map<OutPoint, UtxoEntry> coins = new HashMap<>();
        for (var input : transaction.inputs()) {
            OutPoint point = input.previousOutput();
            if (evicted.contains(point.transactionId())) throw new MempoolAdmissionException("Replacement spends an evicted parent");
            MempoolEntry parent = entries.get(point.transactionId());
            if (parent != null) {
                long index = point.outputIndex().value();
                if (index >= parent.transaction().outputs().size()) throw new MempoolAdmissionException("Invalid parent output index");
                var output = parent.transaction().outputs().get((int) index);
                coins.put(point, new UtxoEntry(output.value(), output.scriptPubKey(), context.nextBlockHeight(), false));
            } else {
                coins.put(point, chainUtxos.find(point).orElseThrow(() -> new MempoolAdmissionException("Missing UTXO: " + point)));
            }
        }
        UtxoView view = point -> Optional.ofNullable(coins.get(point));
        long fee = MempoolValidator.validate(transaction, context, view);
        long sigops = ru.bitcoin.node.consensus.transaction.TransactionSigOpCost.calculate(transaction, view,
                ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags.STANDARD);
        long feeDelta = feeDeltas.getOrDefault(txId, 0L);
        long modifiedFee = Math.addExact(fee, feeDelta);
        if (!packageMode) policy.validateFee(modifiedFee, Math.max(weight, Math.multiplyExact(sigops, 80)));
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateDustFee(transaction, modifiedFee, policy.dustRelaySatPerKvB());
        PackagePolicy.ephemeralSpends(transaction, entries, policy.dustRelaySatPerKvB());
        long arrivalTime = restoredArrivalTime != null ? restoredArrivalTime : clock.instant().getEpochSecond();
        long admissionHeight = restoredAdmissionHeight != null ? restoredAdmissionHeight : Math.max(0L, context.nextBlockHeight() - 1L);
        var entry = new MempoolEntry(transaction, fee, weight, arrivalTime, sigops, feeDelta, admissionHeight);
        if (!packageMode && entry.modifiedFee() < new FeeRate(minimumFeeRate()).feeForVSize(entry.virtualSize())) throw new MempoolAdmissionException("mempool-min-fee");
        MempoolGraphPolicy.replacement(entries, conflicts, evicted, entry, limits);
        Map<Hash256, MempoolEntry> candidate = new LinkedHashMap<>(entries);
        evicted.forEach(candidate::remove);
        candidate.put(txId, entry);
        if (!bypassLimits) MempoolGraphPolicy.checkLimits(candidate, txId, limits);
        long removedRate = packageMode ? 0 : MempoolGraphPolicy.trim(candidate, limits.maxPoolVirtualBytes(), txId);
        entries.clear();
        entries.putAll(candidate);
        unbroadcast.retainAll(entries.keySet());
        rebuildSpent();
        if (removedRate > 0) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        sequence++;
        return entry;
    }

    /** Atomic child-with-independent-parents package admission. Existing entries are
     * deduplicated by txid/wtxid and cannot subsidize a new child a second time.
     */
    public synchronized List<MempoolEntry> admitPackage(List<Transaction> transactions, MempoolValidationContext context, UtxoView chainUtxos) {
        List<Transaction> txs = List.copyOf(transactions);
        Objects.requireNonNull(context); Objects.requireNonNull(chainUtxos);
        PackagePolicy.validate(txs);
        if (txs.size() == 1 && !entries.containsKey(txs.getFirst().txId())) return List.of(admit(txs.getFirst(), context, chainUtxos));
        Set<Hash256> conflicts = new HashSet<>();
        List<Transaction> fresh = new ArrayList<>();
        for (var tx : txs) {
            var known = entries.get(tx.txId());
            if (known != null) {
                if (!known.transaction().wtxId().equals(tx.wtxId())) throw new MempoolAdmissionException("package-different-witness");
                continue;
            }
            fresh.add(tx);
            for (var in : tx.inputs()) {
                Hash256 conflict = spent.get(in.previousOutput());
                if (conflict != null) conflicts.add(conflict);
            }
        }
        Set<Hash256> removed = MempoolGraphPolicy.descendants(entries, conflicts);
        for (var tx : txs) {
            if (removed.contains(tx.txId())) throw new MempoolAdmissionException("package-replaces-own-parent");
            for (var in : tx.inputs()) if (removed.contains(in.previousOutput().transactionId())) throw new MempoolAdmissionException("package-spends-removed-parent");
        }
        Mempool staged = new Mempool(policy, limits, clock);
        staged.entries.putAll(entries);
        staged.feeDeltas.putAll(feeDeltas);
        staged.unbroadcast.addAll(unbroadcast);
        removed.forEach(staged.entries::remove);
        staged.rebuildSpent();
        long fee = 0, size = 0;
        long floor = Math.max(minimumFeeRate(), policy.minRelayFeeRate().satoshisPerKiloByte());
        for (var tx : fresh) {
            var entry = staged.admitInternal(tx, context, chainUtxos, true);
            // Parents that pass individual admission do not contribute fees to a low-fee child.
            if (tx == txs.getLast() || entry.modifiedFee() < new FeeRate(floor).feeForVSize(entry.virtualSize())) {
                fee = Math.addExact(fee, entry.modifiedFee()); size = Math.addExact(size, entry.virtualSize());
            }
        }
        if (size > 0 && fee < new FeeRate(floor).feeForVSize(size)) throw new MempoolAdmissionException("package-feerate");
        PackagePolicy.replacement(fresh, entries, staged.entries, conflicts, removed, limits);
        // Check limits again with the complete package; no carve-outs or sibling eviction.
        for (var tx : fresh) MempoolGraphPolicy.checkLimits(staged.entries, tx.txId(), limits);
        long removedRate = MempoolGraphPolicy.trim(staged.entries, limits.maxPoolVirtualBytes(), txs.getLast().txId());
        List<MempoolEntry> result = txs.stream().map(tx -> staged.entries.get(tx.txId())).toList();
        if (result.stream().anyMatch(Objects::isNull)) throw new MempoolAdmissionException("package-evicted");
        entries.clear(); entries.putAll(staged.entries); unbroadcast.retainAll(entries.keySet()); rebuildSpent();
        if (removedRate > 0) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        if (!fresh.isEmpty() || !removed.isEmpty()) sequence++;
        return result;
    }

    /** Result of a non-mutating mempool/package acceptance probe. */
    public synchronized void markUnbroadcast(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        if (entries.containsKey(txid)) unbroadcast.add(txid);
    }

    public synchronized boolean acknowledgeBroadcast(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        Hash256 txid = null;
        if (entries.containsKey(hash)) txid = hash;
        else for (MempoolEntry entry : entries.values()) {
            if (entry.transaction().wtxId().equals(hash)) { txid = entry.transaction().txId(); break; }
        }
        return txid != null && unbroadcast.remove(txid);
    }

    public synchronized boolean isUnbroadcast(Hash256 txid) { return unbroadcast.contains(txid); }
    public synchronized Set<Hash256> unbroadcastTransactions() { return Set.copyOf(unbroadcast); }

    public record TestAcceptResult(List<MempoolEntry> entries, String rejectReason) {
        public TestAcceptResult { entries = List.copyOf(entries); }
        public boolean allowed() { return rejectReason == null; }
    }

    /**
     * Runs the normal admission path against an isolated clone. No entries, spent map or
     * rolling fee state of this mempool are modified. This is the backing primitive for
     * Core-style testmempoolaccept.
     */
    public synchronized TestAcceptResult testAccept(List<Transaction> transactions,
                                                    MempoolValidationContext context, UtxoView chainUtxos) {
        Objects.requireNonNull(transactions); Objects.requireNonNull(context); Objects.requireNonNull(chainUtxos);
        Mempool staged = new Mempool(policy, limits, clock);
        staged.entries.putAll(entries);
        staged.feeDeltas.putAll(feeDeltas);
        staged.rebuildSpent();
        try {
            List<MempoolEntry> accepted = transactions.size() == 1
                    ? List.of(staged.admit(transactions.getFirst(), context, chainUtxos))
                    : staged.admitPackage(transactions, context, chainUtxos);
            return new TestAcceptResult(accepted, null);
        } catch (MempoolAdmissionException e) {
            return new TestAcceptResult(List.of(), e.getMessage());
        }
    }

    /** Apply a local policy/mining fee delta. The delta is retained even when txid is not yet in mempool. */
    public synchronized long prioritise(Hash256 txId, long feeDelta) {
        Objects.requireNonNull(txId, "txId");
        MempoolEntry existing = entries.get(txId);
        if (feeDelta != 0 && existing != null && existing.transaction().outputs().stream().anyMatch(out ->
                out.value() < ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.dustThreshold(out, policy.dustRelaySatPerKvB())))
            throw new MempoolAdmissionException("Priority is not supported for transactions with dust outputs");
        long updated = Math.addExact(feeDeltas.getOrDefault(txId, 0L), feeDelta);
        if (updated == 0) feeDeltas.remove(txId); else feeDeltas.put(txId, updated);
        MempoolEntry current = entries.get(txId);
        if (current != null) entries.put(txId, current.withFeeDelta(updated));
        return updated;
    }

    public synchronized Map<Hash256, Long> prioritisedTransactions() {
        return Map.copyOf(feeDeltas);
    }

    /** Restore an absolute persisted delta without compounding it. */
    public synchronized void restoreFeeDelta(Hash256 txId, long feeDelta) {
        Objects.requireNonNull(txId, "txId");
        if (feeDelta == 0) feeDeltas.remove(txId); else feeDeltas.put(txId, feeDelta);
        MempoolEntry current = entries.get(txId);
        if (current != null) entries.put(txId, current.withFeeDelta(feeDelta));
    }

    public synchronized boolean contains(Hash256 txId) { return entries.containsKey(Objects.requireNonNull(txId)); }
    public synchronized Optional<MempoolEntry> find(Hash256 txId) { return Optional.ofNullable(entries.get(Objects.requireNonNull(txId))); }

    /** Eviction removes descendants as their unconfirmed inputs would otherwise disappear. */
    public synchronized Optional<MempoolEntry> remove(Hash256 txId) {
        Objects.requireNonNull(txId);
        MempoolEntry root = entries.get(txId);
        if (root == null) return Optional.empty();
        Set<Hash256> removed = new HashSet<>();
        removed.add(txId);
        // Insertion order is topological: parents must already exist when children enter.
        for (var entry : entries.values()) {
            if (entry.transaction().inputs().stream().anyMatch(in -> removed.contains(in.previousOutput().transactionId()))) {
                removed.add(entry.transaction().txId());
            }
        }
        for (Hash256 id : removed) {
            MempoolEntry entry = entries.remove(id);
            for (var input : entry.transaction().inputs()) spent.remove(input.previousOutput(), id);
        }
        unbroadcast.retainAll(entries.keySet());
        sequence++;
        return Optional.of(root);
    }

    public synchronized int size() { return entries.size(); }

    /** Recheck after a chain update under the caller's chain lock. Confirmed IDs are
     * omitted; their children can spend the corresponding coins in the new UTXO view.
     * Unexpected storage/history failures leave the previous pool intact.
     */
    public synchronized List<Hash256> revalidate(MempoolValidationContext context, UtxoView chainUtxos,
                                                 Set<Hash256> confirmed) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(chainUtxos);
        Objects.requireNonNull(confirmed);
        Mempool replacement = new Mempool(policy, limits, clock);
        replacement.feeDeltas.putAll(feeDeltas);
        replacement.unbroadcast.addAll(unbroadcast);
        List<Hash256> removed = new ArrayList<>();
        for (var entry : entries.values()) {
            Hash256 id = entry.transaction().txId();
            if (confirmed.contains(id)) {
                removed.add(id);
                continue;
            }
            try {
                replacement.admitInternal(entry.transaction(), context, chainUtxos, true, true);
                replacement.entries.put(id, entry); // Keep original arrival time and metadata.
            } catch (MempoolAdmissionException
                     | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException
                     | ru.bitcoin.node.script.ScriptParseException e) {
                removed.add(id);
            }
        }
        entries.clear();
        entries.putAll(replacement.entries);
        unbroadcast.retainAll(entries.keySet());
        spent.clear();
        spent.putAll(replacement.spent);
        rollingFee.blockConnected(clock.instant().getEpochSecond());
        if (!removed.isEmpty()) sequence++;
        return List.copyOf(removed);
    }
    /** Chain reconciliation, including disconnected transactions, commits as one operation. */
    public synchronized void reconcile(MempoolValidationContext context, UtxoView chainUtxos,
                                       Set<Hash256> confirmed, List<Transaction> disconnected) {
        Set<Hash256> beforeIds = Set.copyOf(entries.keySet());
        Mempool staged = new Mempool(policy, limits, clock);
        staged.entries.putAll(entries);
        staged.feeDeltas.putAll(feeDeltas);
        staged.unbroadcast.addAll(unbroadcast);
        confirmed.forEach(staged.entries::remove);
        staged.rebuildSpent();
        for (var tx : disconnected) {
            if (confirmed.contains(tx.txId()) || staged.contains(tx.txId())) continue;
            try {
                // Bypass admission fee floors and TRUC, but retain ordinary RBF checks
                // against the surviving pool, including descendant fees.
                staged.admitInternal(tx, context, chainUtxos, true, true);
            } catch (MempoolAdmissionException | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException | ru.bitcoin.node.script.ScriptParseException expected) {
                // A failed resurrection makes existing descendants orphaned.
                for (var entry : List.copyOf(staged.entries.values())) {
                    if (entry.transaction().inputs().stream().anyMatch(in -> in.previousOutput().transactionId().equals(tx.txId()))) {
                        staged.remove(entry.transaction().txId());
                    }
                }
            }
        }
        // Resurrected parents may have been inserted after surviving children.
        // Restore topological order before validating against the new chain snapshot.
        Map<Hash256, MempoolEntry> pending = new LinkedHashMap<>(staged.entries);
        staged.entries.clear();
        while (!pending.isEmpty()) {
            int before = pending.size();
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().transaction().inputs().stream()
                        .anyMatch(in -> pending.containsKey(in.previousOutput().transactionId()))) continue;
                staged.entries.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
            if (pending.size() == before) throw new IllegalStateException("Cyclic mempool dependencies");
        }
        staged.rebuildSpent();
        staged.revalidate(context, chainUtxos, confirmed);        staged.expire();
        int beforeTrim = staged.size();
        long removedRate = MempoolGraphPolicy.trim(staged.entries, limits.maxPoolVirtualBytes(), null);
        entries.clear(); entries.putAll(staged.entries);
        unbroadcast.clear(); unbroadcast.addAll(staged.unbroadcast); unbroadcast.retainAll(entries.keySet());
        rebuildSpent();
        rollingFee.blockConnected(clock.instant().getEpochSecond());
        if (staged.size() < beforeTrim) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        if (!beforeIds.equals(entries.keySet())) sequence++;
    }
    public synchronized boolean isEmpty() { return entries.isEmpty(); }
    public synchronized List<MempoolEntry> entries() { return List.copyOf(entries.values()); }

    /** Atomic transaction-set snapshot paired with the mutation sequence. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(entries.values()), sequence);
    }

    /** Atomic full graph snapshot for verbose getrawmempool. */
    public synchronized DetailedSnapshot detailedSnapshot() {
        List<MempoolEntry> values = List.copyOf(entries.values());
        return new DetailedSnapshot(values, sequence, graphViews(entries.keySet()));
    }

    /** In-mempool ancestors excluding the transaction itself. */
    public synchronized Optional<List<Hash256>> ancestors(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        if (!entries.containsKey(txId)) return Optional.empty();
        Set<Hash256> ids = new LinkedHashSet<>(MempoolGraphPolicy.ancestors(entries, txId));
        ids.remove(txId);
        return Optional.of(List.copyOf(ids));
    }

    /** In-mempool descendants excluding the transaction itself. */
    public synchronized Optional<List<Hash256>> descendants(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        if (!entries.containsKey(txId)) return Optional.empty();
        Set<Hash256> ids = new LinkedHashSet<>(MempoolGraphPolicy.descendants(entries, Set.of(txId)));
        ids.remove(txId);
        return Optional.of(List.copyOf(ids));
    }

    public synchronized Optional<GraphQuery> ancestorQuery(Hash256 txId) {
        Optional<List<Hash256>> ids = ancestors(txId);
        return ids.map(value -> new GraphQuery(value, graphViews(value)));
    }

    public synchronized Optional<GraphQuery> descendantQuery(Hash256 txId) {
        Optional<List<Hash256>> ids = descendants(txId);
        return ids.map(value -> new GraphQuery(value, graphViews(value)));
    }

    public record Snapshot(List<MempoolEntry> entries, long sequence) {
        public Snapshot { entries = List.copyOf(entries); }
    }

    public record DetailedSnapshot(List<MempoolEntry> entries, long sequence, Map<Hash256, EntryGraphView> graphViews) {
        public DetailedSnapshot {
            entries = List.copyOf(entries);
            graphViews = Map.copyOf(graphViews);
        }
    }

    public record GraphQuery(List<Hash256> txIds, Map<Hash256, EntryGraphView> graphViews) {
        public GraphQuery {
            txIds = List.copyOf(txIds);
            graphViews = Map.copyOf(graphViews);
        }
    }

    /** Atomic graph views for a selected set of current mempool transactions. */
    public synchronized Map<Hash256, EntryGraphView> graphViews(Collection<Hash256> txIds) {
        Objects.requireNonNull(txIds, "txIds");
        Map<Hash256, EntryGraphView> result = new LinkedHashMap<>();
        for (Hash256 txId : txIds) {
            EntryGraphView view = graphView(txId).orElse(null);
            if (view != null) result.put(txId, view);
        }
        return Map.copyOf(result);
    }

    /** Atomic Core-v31-style view of one connected mempool cluster. */
    public synchronized Optional<ClusterView> cluster(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        if (!entries.containsKey(txId)) return Optional.empty();
        Set<Hash256> ids = ClusterLinearization.connected(entries, List.of(txId));
        List<ClusterLinearization.Chunk> chunks = ClusterLinearization.chunks(entries, ids);
        long adjustedWeight = ids.stream().mapToLong(id -> entries.get(id).adjustedWeight()).sum();
        return Optional.of(new ClusterView(adjustedWeight, ids.size(), chunks));
    }

    /** Atomic whole-mempool feerate diagram, in descending mining order. */
    public synchronized List<ClusterLinearization.Chunk> feeRateDiagram() {
        return ClusterLinearization.mempoolDiagram(entries);
    }

    /** Atomic graph data used by getmempoolentry. Sets include the transaction itself. */
    public synchronized Optional<EntryGraphView> graphView(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        MempoolEntry entry = entries.get(txId);
        if (entry == null) return Optional.empty();
        Set<Hash256> ancestors = MempoolGraphPolicy.ancestors(entries, txId);
        Set<Hash256> descendants = MempoolGraphPolicy.descendants(entries, Set.of(txId));
        Set<Hash256> parents = new LinkedHashSet<>();
        for (var input : entry.transaction().inputs()) {
            Hash256 parent = input.previousOutput().transactionId();
            if (entries.containsKey(parent)) parents.add(parent);
        }
        Set<Hash256> children = new LinkedHashSet<>();
        for (var candidate : entries.entrySet()) {
            if (candidate.getValue().transaction().inputs().stream()
                    .anyMatch(input -> input.previousOutput().transactionId().equals(txId))) children.add(candidate.getKey());
        }
        Set<Hash256> cluster = ClusterLinearization.connected(entries, List.of(txId));
        ClusterLinearization.Chunk chunk = ClusterLinearization.chunks(entries, cluster).stream()
                .filter(value -> value.transactions().contains(txId)).findFirst().orElseThrow();
        long ancestorVsize = ancestors.stream().mapToLong(id -> entries.get(id).virtualSize()).sum();
        long descendantVsize = descendants.stream().mapToLong(id -> entries.get(id).virtualSize()).sum();
        long ancestorFee = ancestors.stream().mapToLong(id -> entries.get(id).modifiedFee()).sum();
        long descendantFee = descendants.stream().mapToLong(id -> entries.get(id).modifiedFee()).sum();
        return Optional.of(new EntryGraphView(entry, ancestors, descendants, Set.copyOf(parents), Set.copyOf(children), chunk,
                ancestorVsize, descendantVsize, ancestorFee, descendantFee));
    }

    public record ClusterView(long adjustedWeight, int transactionCount, List<ClusterLinearization.Chunk> chunks) {
        public ClusterView { chunks = List.copyOf(chunks); }
    }

    public record EntryGraphView(MempoolEntry entry, Set<Hash256> ancestors, Set<Hash256> descendants,
                                 Set<Hash256> parents, Set<Hash256> children, ClusterLinearization.Chunk chunk,
                                 long ancestorVirtualSize, long descendantVirtualSize,
                                 long ancestorFee, long descendantFee) {
        public EntryGraphView {
            Objects.requireNonNull(entry); ancestors = Set.copyOf(ancestors); descendants = Set.copyOf(descendants);
            parents = Set.copyOf(parents); children = Set.copyOf(children); Objects.requireNonNull(chunk);
        }
    }

    /** Returns the mempool transaction currently spending the outpoint, if any. */
    public synchronized Optional<Transaction> spendingTransaction(OutPoint outPoint) {
        Objects.requireNonNull(outPoint, "outPoint");
        Hash256 spender = spent.get(outPoint);
        if (spender == null) return Optional.empty();
        MempoolEntry entry = entries.get(spender);
        return entry == null ? Optional.empty() : Optional.of(entry.transaction());
    }

    public synchronized int expire() {
        long cutoff = clock.instant().getEpochSecond() - limits.expirySeconds();
        int before = entries.size();
        for (var entry : List.copyOf(entries.values())) {
            if (entry.arrivalTime() < cutoff) remove(entry.transaction().txId());
        }
        return before - entries.size();
    }

    private void rebuildSpent() {
        spent.clear();
        for (var entry : entries.values()) for (var input : entry.transaction().inputs()) {
            spent.put(input.previousOutput(), entry.transaction().txId());
        }
    }
    public synchronized long minimumFeeRate() {
        return rollingFee.get(clock.instant().getEpochSecond(), entries.values().stream().mapToLong(MempoolEntry::virtualSize).sum(),
                limits.maxPoolVirtualBytes(), limits.incrementalRelaySatPerKvB());
    }

    /**
     * Current BIP133 filter floor before privacy quantization.
     *
     * Bitcoin Core never advertises a fee filter below the node's configured
     * minimum relay fee, even when the rolling mempool minimum is lower.
     */
    public synchronized long feeFilterRate() {
        return Math.max(
                minimumFeeRate(),
                policy.minRelayFeeRate().satoshisPerKiloByte()
        );
    }

    public synchronized long minimumRelayFeeRate() {
        return policy.minRelayFeeRate().satoshisPerKiloByte();
    }
}
