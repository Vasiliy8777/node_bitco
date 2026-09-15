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
        policy.validateFee(fee, Math.max(weight, sigops * 20));
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateDustFee(transaction, fee);
        var entry = new MempoolEntry(transaction, fee, weight, clock.instant().getEpochSecond(), sigops);
        MempoolGraphPolicy.replacement(entries, conflicts, evicted, entry, limits);
        Map<Hash256, MempoolEntry> candidate = new LinkedHashMap<>(entries);
        evicted.forEach(candidate::remove);
        candidate.put(txId, entry);
        MempoolGraphPolicy.checkLimits(candidate, txId, limits);
        MempoolGraphPolicy.trim(candidate, limits.maxPoolVirtualBytes(), txId);
        entries.clear();
        entries.putAll(candidate);
        rebuildSpent();
        return entry;
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
                replacement.admit(entry.transaction(), context, chainUtxos);
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
        return List.copyOf(removed);
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
}
