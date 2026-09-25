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

    public Mempool() { this(new MempoolPolicy()); }
    public Mempool(MempoolPolicy policy) { this(policy, MempoolLimits.DEFAULT, java.time.Clock.systemUTC()); }
    public Mempool(MempoolPolicy policy, MempoolLimits limits, java.time.Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized MempoolEntry admit(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos) {
        return admitInternal(transaction, context, chainUtxos, false);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos, boolean packageMode) {
        return admitInternal(transaction, context, chainUtxos, packageMode, false);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos, boolean packageMode, boolean bypassLimits) {
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
        if (!packageMode) policy.validateFee(fee, Math.max(weight, sigops * 20));
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateDustFee(transaction, fee, policy.dustRelaySatPerKvB());
        PackagePolicy.ephemeralSpends(transaction, entries, policy.dustRelaySatPerKvB());
        var entry = new MempoolEntry(transaction, fee, weight, clock.instant().getEpochSecond(), sigops);
        if (!packageMode && fee < new FeeRate(minimumFeeRate()).feeForVSize(entry.virtualSize())) throw new MempoolAdmissionException("mempool-min-fee");
        MempoolGraphPolicy.replacement(entries, conflicts, evicted, entry, limits);
        Map<Hash256, MempoolEntry> candidate = new LinkedHashMap<>(entries);
        evicted.forEach(candidate::remove);
        candidate.put(txId, entry);
        if (!bypassLimits) MempoolGraphPolicy.checkLimits(candidate, txId, limits, !packageMode);
        long removedRate = packageMode ? 0 : MempoolGraphPolicy.trim(candidate, limits.maxPoolVirtualBytes(), txId);
        entries.clear();
        entries.putAll(candidate);
        rebuildSpent();
        if (removedRate > 0) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
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
        removed.forEach(staged.entries::remove);
        staged.rebuildSpent();
        long fee = 0, size = 0;
        long floor = Math.max(minimumFeeRate(), policy.minRelayFeeRate().satoshisPerKiloByte());
        for (var tx : fresh) {
            var entry = staged.admitInternal(tx, context, chainUtxos, true);
            // Parents that pass individual admission do not contribute fees to a low-fee child.
            if (tx == txs.getLast() || entry.fee() < new FeeRate(floor).feeForVSize(entry.virtualSize())) {
                fee = Math.addExact(fee, entry.fee()); size = Math.addExact(size, entry.virtualSize());
            }
        }
        if (size > 0 && fee < new FeeRate(floor).feeForVSize(size)) throw new MempoolAdmissionException("package-feerate");
        PackagePolicy.replacement(fresh, entries, staged.entries, conflicts, removed, limits);
        // Check limits again with the complete package; no carve-outs or sibling eviction.
        for (var tx : fresh) MempoolGraphPolicy.checkLimits(staged.entries, tx.txId(), limits, false);
        long removedRate = MempoolGraphPolicy.trim(staged.entries, limits.maxPoolVirtualBytes(), txs.getLast().txId());
        List<MempoolEntry> result = txs.stream().map(tx -> staged.entries.get(tx.txId())).toList();
        if (result.stream().anyMatch(Objects::isNull)) throw new MempoolAdmissionException("package-evicted");
        entries.clear(); entries.putAll(staged.entries); rebuildSpent();
        if (removedRate > 0) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        return result;
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
        spent.clear();
        spent.putAll(replacement.spent);
        rollingFee.blockConnected(clock.instant().getEpochSecond());
        return List.copyOf(removed);
    }
    /** Chain reconciliation, including disconnected transactions, commits as one operation. */
    public synchronized void reconcile(MempoolValidationContext context, UtxoView chainUtxos,
                                       Set<Hash256> confirmed, List<Transaction> disconnected) {
        Mempool staged = new Mempool(policy, limits, clock);
        staged.entries.putAll(entries);
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
        entries.clear(); entries.putAll(staged.entries); rebuildSpent();
        rollingFee.blockConnected(clock.instant().getEpochSecond());
        if (staged.size() < beforeTrim) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
    }
    public synchronized boolean isEmpty() { return entries.isEmpty(); }
    public synchronized List<MempoolEntry> entries() { return List.copyOf(entries.values()); }

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
