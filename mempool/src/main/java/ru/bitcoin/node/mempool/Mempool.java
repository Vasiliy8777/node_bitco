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
    /** Keeps missing-input classification independent of diagnostic text. */
    private static final class MissingInput extends MempoolAdmissionException {
        MissingInput(String message) { super(message); }
        @Override public boolean isMissingInputs() { return true; }
    }
    private static final class ProbeScriptFailure extends MempoolAdmissionException {
        final String details;
        ProbeScriptFailure(MempoolValidator.ScriptFailure failure) { super(failure.reason()); details = failure.details(); }
    }
    /** Enabled only on an isolated testmempoolaccept clone. */
    private boolean probeScriptDiagnostics;
    private boolean admissionProbe;
    private final MempoolPolicy policy;
    private final MempoolLimits limits;
    private final java.time.Clock clock;
    private final RollingMinimumFee rollingFee = new RollingMinimumFee();
    private final Map<Hash256, MempoolEntry> entries = new LinkedHashMap<>();
    private final MempoolGraphIndex graphIndex = new MempoolGraphIndex();
    private final Map<OutPoint, Hash256> spent = new HashMap<>();
    private long totalVirtualBytes;
    private long randomizedCapacity;
    private long totalEntryMemory;
    private record Arrival(long time, Hash256 id) implements Comparable<Arrival> {
        public int compareTo(Arrival other) {
            int timeOrder=Long.compare(time,other.time);
            return timeOrder!=0?timeOrder:id.compareRawBytes(other.id);
        }
    }
    private final NavigableSet<Arrival> arrivalIndex = new TreeSet<>();
    /** Successful admission's RBF removals, excluding memory/expiry eviction. */
    private Set<Hash256> lastReplacedTransactions = Set.of();
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
        return admitInternal(transaction, context, chainUtxos, packageMode, bypassLimits,
                restoredArrivalTime, restoredAdmissionHeight, packageMode);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos,
                                       boolean packageMode, boolean bypassLimits, Long restoredArrivalTime,
                                       Long restoredAdmissionHeight, boolean packageFeerates) {
        return admitInternal(transaction, context, chainUtxos, packageMode, bypassLimits,
                restoredArrivalTime, restoredAdmissionHeight, packageFeerates, null);
    }
    private record DeferredScript(Transaction transaction, UtxoView coins) {}
    private void validateOrDeferScripts(MempoolEntry entry, UtxoView coins, List<DeferredScript> deferred, Long clientMaxFeeRate) {
        var transaction = entry.transaction();
        if (clientMaxFeeRate != null && clientMaxFeeRate != 0) {
            var entryFee = java.math.BigInteger.valueOf(entry.modifiedFee()).multiply(java.math.BigInteger.valueOf(1000))
                    .divide(java.math.BigInteger.valueOf(entry.virtualSize()));
            if (entryFee.compareTo(java.math.BigInteger.valueOf(clientMaxFeeRate)) > 0)
                throw new MempoolAdmissionException("max feerate exceeded");
        }
        if (deferred != null) deferred.add(new DeferredScript(transaction, coins));
        else if (probeScriptDiagnostics || clientMaxFeeRate != null) {
            var failure = MempoolValidator.testScripts(transaction, coins);
            if (failure != null) throw new ProbeScriptFailure(failure);
        } else MempoolValidator.validateScripts(transaction, coins);
    }
    /** Deferred scripts are held only by isolated package/probe staging pools. */
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos,
                                       boolean packageMode, boolean bypassLimits, Long restoredArrivalTime,
                                       Long restoredAdmissionHeight, boolean packageFeerates, List<DeferredScript> deferred) {
        return admitInternal(transaction, context, chainUtxos, packageMode, bypassLimits, restoredArrivalTime,
                restoredAdmissionHeight, packageFeerates, deferred, null);
    }
    private MempoolEntry admitInternal(Transaction transaction, MempoolValidationContext context, UtxoView chainUtxos,
                                       boolean packageMode, boolean bypassLimits, Long restoredArrivalTime,
                                       Long restoredAdmissionHeight, boolean packageFeerates, List<DeferredScript> deferred,
                                       Long clientMaxFeeRate) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(chainUtxos, "chainUtxos");
        lastReplacedTransactions = Set.of();
        Hash256 txId = transaction.txId();
        var existing = entries.get(txId);
        // Identical immutable serialization already passed structural validation.
        // Finality is contextual and must still precede the known-entry result.
        if (existing != null && existing.transaction().wtxId().equals(transaction.wtxId())) {
            if (!ru.bitcoin.node.consensus.transaction.TransactionFinality.isFinal(transaction,context.nextBlockHeight(),context.tipMedianTimePast()))
                throw new ru.bitcoin.node.consensus.transaction.TransactionValidationException("Transaction is non-final","non-final",null);
            throw new MempoolAdmissionException("txn-already-in-mempool");
        }
        ru.bitcoin.node.consensus.transaction.TransactionValidator.validateBasic(transaction);
        if (transaction.isCoinbase()) throw new MempoolPolicyException("Coinbase cannot enter mempool","coinbase",null);
        long weight = TransactionWeight.calculate(transaction);
        policy.validateStandardStructure(transaction, weight);
        if (!ru.bitcoin.node.consensus.transaction.TransactionFinality.isFinal(transaction,context.nextBlockHeight(),context.tipMedianTimePast()))
            throw new ru.bitcoin.node.consensus.transaction.TransactionValidationException("Transaction is non-final","non-final",null);
        if (existing != null) throw new MempoolAdmissionException(
                existing.transaction().wtxId().equals(transaction.wtxId())
                        ? "txn-already-in-mempool" : "txn-same-nonwitness-data-in-mempool");
        Set<Hash256> conflicts = new HashSet<>();
        for (var input : transaction.inputs()) {
            Hash256 conflict = spent.get(input.previousOutput());
            if (conflict != null) conflicts.add(conflict);
        }
        if (!packageMode) {
            MempoolGraphPolicy.addSiblingConflict(entries, spent, transaction, conflicts);
            MempoolGraphPolicy.checkConflictClusterLimit(entries, conflicts, "too-many-conflicting-clusters");
        }
        Set<Hash256> evicted = graphIndex.descendants(conflicts);
        Map<OutPoint, UtxoEntry> coins = new HashMap<>();
        for (var input : transaction.inputs()) {
            OutPoint point = input.previousOutput();
            if (evicted.contains(point.transactionId())) throw new MempoolAdmissionException("Replacement spends an evicted parent");
            MempoolEntry parent = entries.get(point.transactionId());
            if (parent != null) {
                long index = point.outputIndex().value();
                if (index >= parent.transaction().outputs().size()) throw new MissingInput("Invalid parent output index");
                var output = parent.transaction().outputs().get((int) index);
                coins.put(point, new UtxoEntry(output.value(), output.scriptPubKey(), context.nextBlockHeight(), false));
            } else {
                coins.put(point, chainUtxos.find(point).orElseThrow(() -> new MissingInput("Missing UTXO: " + point)));
            }
        }
        UtxoView view = point -> Optional.ofNullable(coins.get(point));
        long fee = MempoolValidator.validatePreChecks(transaction, context, view);
        long sigops = ru.bitcoin.node.consensus.transaction.TransactionSigOpCost.calculate(transaction, view,
                ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags.STANDARD);
        long feeDelta = feeDeltas.getOrDefault(txId, 0L);
        long modifiedFee = Math.addExact(fee, feeDelta);
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateDustFee(transaction, fee, modifiedFee, policy.dustRelaySatPerKvB());
        if (sigops > 16_000) throw new MempoolPolicyException("too-many-sigops", "bad-txns-too-many-sigops", Long.toString(sigops));
        if (deferred == null) PackagePolicy.ephemeralSpends(transaction, entries, policy.dustRelaySatPerKvB());
        long arrivalTime = restoredArrivalTime != null ? restoredArrivalTime : clock.instant().getEpochSecond();
        long admissionHeight = restoredAdmissionHeight != null ? restoredAdmissionHeight : Math.max(0L, context.nextBlockHeight() - 1L);
        var entry = new MempoolEntry(transaction, fee, weight, arrivalTime, sigops, feeDelta, admissionHeight);
        if (!packageFeerates) {
            long rollingMinimum = new FeeRate(minimumFeeRate()).feeForVSize(entry.virtualSize());
            // Core checks a positive rolling floor before minrelay. A zero
            // floor must not misclassify a negative modified fee as a rolling rejection.
            if (rollingMinimum > 0 && entry.modifiedFee() < rollingMinimum)
                throw new MempoolFeeException("mempool-min-fee", "mempool min fee not met", entry.modifiedFee(), rollingMinimum);
            policy.validateFee(modifiedFee, entry.adjustedWeight());
        }
        // Core's individual TRUC precheck precedes economic RBF evaluation.
        if (!bypassLimits && deferred == null) MempoolGraphPolicy.checkSingleTruc(entries, spent, entry, entries, conflicts);
        var replacementGraph = new MempoolGraphIndex[1];
        MempoolGraphPolicy.replacement(entries, conflicts, evicted, entry, limits, after -> {
            var diagram = replacementDiagrams(after,conflicts,List.of(transaction),null);
            replacementGraph[0]=diagram.graph(); return diagram.comparison();
        });
        // Conflict-free additions only affect their connected component.
        // Preserve staged RBF/eviction and the package capacity boundary.
        if (conflicts.isEmpty()) {
            if (!bypassLimits && deferred == null) MempoolGraphPolicy.checkLimits(
                    MempoolGraphPolicy.additionCluster(entries, spent, entry, limits.clusterCount()), txId, limits);
            long proposedSize = Math.addExact(totalVirtualBytes, entry.virtualSize());
            // No removal or eviction is needed. All validation precedes commit;
            // package admission retains its existing deferred-capacity boundary.
            long proposedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size(), 0, 1);
            long memoryUpperBound = totalEntryMemory + CoreMemoryUsage.entry(entry)
                    + CoreMemoryUsage.allocation(40 * proposedCapacity) + 96L * feeDeltas.size()
                    + 400L * (entries.size() + 1);
            if (packageMode || admissionProbe || memoryUpperBound <= limits.maxPoolMemoryBytes()) {
                validateOrDeferScripts(entry, view, deferred, clientMaxFeeRate);
                entries.put(txId, entry);
                for (var input : transaction.inputs()) spent.put(input.previousOutput(), txId);
                indexAddition(entry);
                totalVirtualBytes = proposedSize;
                sequence++;
                if (!packageMode && !admissionProbe) {
                    finishGraphChange();
                    expire();
                    if (!entries.containsKey(txId)) throw new MempoolPolicyException("mempool-full","mempool full",null);
                }
                return entry;
            }
        }
        Map<Hash256, MempoolEntry> candidate = new LinkedHashMap<>(entries);
        evicted.forEach(candidate::remove);
        candidate.put(txId, entry);
        if (!bypassLimits && deferred == null) {
            if (packageMode) MempoolGraphPolicy.checkLimits(candidate, txId, limits);
            else MempoolGraphPolicy.checkLimits(candidate, txId, limits, entries, conflicts);
        }
        validateOrDeferScripts(entry, view, deferred, clientMaxFeeRate);
        MempoolGraphIndex prepared = replacementGraph[0];
        MemoryTrim memoryTrim = null;
        if (!packageMode && !admissionProbe) {
            if (prepared == null) prepared = candidateGraph(candidate, graphIndex);
            prepared.doWork(candidate,ClusterOptimization.POST_CHANGE_COST);
            long capacity = capacityForCandidate(candidate);
            int beforeExpiry = candidate.size();
            expireCandidate(candidate);
            capacity = CoreMemoryUsage.randomizedCapacity(capacity,beforeExpiry,beforeExpiry-candidate.size(),0);
            memoryTrim = trimToMemory(candidate,null,prepared,capacity);
        }
        int beforeTrim = memoryTrim == null ? candidate.size() : memoryTrim.beforeTrim();
        long removedRate = memoryTrim == null ? 0 : memoryTrim.removedRate();
        commitEntries(candidate, memoryTrim == null ? prepared : memoryTrim.graph(), false);
        if (memoryTrim != null) randomizedCapacity = memoryTrim.capacity();
        lastReplacedTransactions = Set.copyOf(evicted);
        if (candidate.size() < beforeTrim) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        sequence++;
        if (!entries.containsKey(txId)) throw new MempoolPolicyException("mempool-full","mempool full",null);
        return entry;
    }

    /** Atomic child-with-independent-parents package admission. Existing entries are
     * deduplicated by txid/wtxid and cannot subsidize a new child a second time.
     */
    public synchronized List<MempoolEntry> admitPackage(List<Transaction> transactions, MempoolValidationContext context, UtxoView chainUtxos) {
        return admitPackageInternal(transactions, context, chainUtxos, true, true);
    }

    /**
     * Package admission with an explicit replacement policy. Bitcoin Core v31 deliberately
     * disables replacement for multi-transaction testmempoolaccept probes, while submitpackage
     * may enter the constrained 1-parent-1-child package-RBF path.
     */
    private List<MempoolEntry> admitPackageInternal(List<Transaction> transactions,
                                                    MempoolValidationContext context,
                                                    UtxoView chainUtxos,
                                                    boolean allowReplacement, boolean packageFeerates) {
        return admitPackageInternal(transactions, context, chainUtxos, allowReplacement, packageFeerates, null);
    }
    private static final class SubmissionFailure extends MempoolAdmissionException {
        final Hash256 wtxid;
        final MempoolAdmissionException failure;
        final String packageMessage;
        SubmissionFailure(Hash256 wtxid, MempoolAdmissionException failure) {
            this(wtxid, failure, "transaction failed");
        }
        SubmissionFailure(Hash256 wtxid, MempoolAdmissionException failure, String packageMessage) {
            super(failure.getMessage()); this.wtxid = wtxid; this.failure = failure; this.packageMessage = packageMessage;
        }
    }
    private static final class SharedPackageFailure extends MempoolAdmissionException {
        SharedPackageFailure(String reason) { super(reason); }
    }
    private List<MempoolEntry> admitPackageInternal(List<Transaction> transactions,
                                                    MempoolValidationContext context, UtxoView chainUtxos,
                                                    boolean allowReplacement, boolean packageFeerates, Long clientMaxFeeRate) {
        List<Transaction> txs = List.copyOf(transactions);
        lastReplacedTransactions = Set.of();
        Objects.requireNonNull(context); Objects.requireNonNull(chainUtxos);
        PackagePolicy.validate(txs, packageFeerates);
        if (txs.size() == 1 && !entries.containsKey(txs.getFirst().txId())) return List.of(
                admitInternal(txs.getFirst(), context, chainUtxos, false, false, null, null, false, null, clientMaxFeeRate));
        Set<Hash256> conflicts = new HashSet<>();
        List<Transaction> fresh = new ArrayList<>();
        for (var tx : txs) {
            var known = entries.get(tx.txId());
            if (known != null) {
                // Core submission retains the accepted witness and reports its
                // wtxid, without validating/replacing the supplied alternative.
                continue;
            }
            fresh.add(tx);
            for (var in : tx.inputs()) {
                Hash256 conflict = spent.get(in.previousOutput());
                if (conflict != null) conflicts.add(conflict);
            }
        }
        if (!allowReplacement && !conflicts.isEmpty())
            throw new MempoolAdmissionException("txn-mempool-conflict");
        if (allowReplacement) PackagePolicy.replacementContext(fresh, entries, conflicts);
        Set<Hash256> removed = graphIndex.descendants(conflicts);
        for (var tx : txs) {
            if (removed.contains(tx.txId())) throw new MempoolAdmissionException("package-replaces-own-parent");
            for (var in : tx.inputs()) if (removed.contains(in.previousOutput().transactionId())) throw new MempoolAdmissionException("package-spends-removed-parent");
        }
        Mempool staged = new Mempool(policy, limits, clock);
        staged.copyPoolStateFrom(this);
        staged.admissionProbe = admissionProbe;
        staged.feeDeltas.putAll(feeDeltas);
        staged.rollingFee.restoreFrom(rollingFee);
        staged.unbroadcast.addAll(unbroadcast);
        staged.removeSeedEntries(removed);
        long fee = 0, size = 0;
        long floor = Math.max(minimumFeeRate(), policy.minRelayFeeRate().satoshisPerKiloByte());
        // RPC retry completes every PreCheck before shared policy and scripts.
        List<DeferredScript> deferred = clientMaxFeeRate != null ? new ArrayList<>() : null;
        for (var tx : fresh) {
            MempoolEntry entry;
            try {
                entry = staged.admitInternal(tx, context, chainUtxos, true, false, null, null, packageFeerates, deferred, clientMaxFeeRate);
                if (deferred != null) MempoolGraphPolicy.checkProbeTruc(entries, spent, entry);
            }
            catch (MempoolAdmissionException failure) {
                if (clientMaxFeeRate != null) throw new SubmissionFailure(tx.wtxId(), failure);
                throw failure;
            }
            // Parents that pass individual admission do not contribute fees to a low-fee child.
            if (packageFeerates && (deferred != null || tx == txs.getLast() || entry.modifiedFee() < new FeeRate(floor).feeForVSize(entry.virtualSize()))) {
                fee = Math.addExact(fee, entry.modifiedFee()); size = Math.addExact(size, entry.virtualSize());
            }
        }
        if (deferred != null) {
            try {
                MempoolGraphPolicy.checkPackageTruc(entries,fresh,staged.entries);
            } catch (MempoolAdmissionException error) { throw new SharedPackageFailure(
                    error instanceof TrucPolicyException truc ? truc.rpcDetails() : "TRUC-violation"); }
        }
        if (size > 0 && fee < new FeeRate(floor).feeForVSize(size)) {
            if (clientMaxFeeRate != null) {
                long rolling = new FeeRate(minimumFeeRate()).feeForVSize(size);
                throw rolling > 0 && fee < rolling
                        ? new MempoolFeeException("mempool-min-fee", "mempool min fee not met", fee, rolling)
                        : new MempoolFeeException("package-feerate", "min relay fee not met", fee, new FeeRate(floor).feeForVSize(size));
            }
            throw new MempoolAdmissionException("package-feerate");
        }
        if (allowReplacement)
            PackagePolicy.replacement(fresh, entries, staged.entries, conflicts, removed, limits,
                    () -> replacementDiagrams(staged.entries,conflicts,fresh,staged.graphIndex).comparison());
        // Check limits again with the complete package; no carve-outs or sibling eviction.
        for (var tx : fresh) {
            if (deferred == null) MempoolGraphPolicy.checkLimits(staged.entries, tx.txId(), limits);
            else try { MempoolGraphPolicy.checkClusterLimits(staged.entries, tx.txId(), limits); }
            catch (MempoolAdmissionException error) { throw new SharedPackageFailure("too-large-cluster"); }
        }
        if (deferred != null) {
            for (var tx : fresh) {
                try { PackagePolicy.ephemeralSpends(tx, staged.entries, policy.dustRelaySatPerKvB()); }
                catch (MempoolAdmissionException error) { throw new SubmissionFailure(tx.wtxId(), error, "unspent-dust"); }
            }
            for (var check : deferred) {
                var failure = MempoolValidator.testScripts(check.transaction(), check.coins());
                if (failure != null) throw new SubmissionFailure(check.transaction().wtxId(), new ProbeScriptFailure(failure));
            }
        }
        if (!admissionProbe) { staged.finishGraphChange(); staged.expire(); }
        int beforeTrim = staged.entries.size();
        var memoryTrim = admissionProbe ? new MemoryTrim(0,staged.randomizedCapacity,staged.graphIndex,beforeTrim) : staged.trimToMemory(staged.entries, null);
        long removedRate = memoryTrim.removedRate();
        List<MempoolEntry> result = txs.stream().map(tx -> staged.entries.get(tx.txId())).toList();
        commitEntries(staged.entries, memoryTrim.graph(), false);
        randomizedCapacity = memoryTrim.capacity();
        lastReplacedTransactions = Set.copyOf(removed);
        if (staged.entries.size() < beforeTrim) rollingFee.bump(removedRate, limits.incrementalRelaySatPerKvB(), clock.instant().getEpochSecond());
        if (!fresh.isEmpty() || !removed.isEmpty()) sequence++;
        for(int i=0;i<result.size();i++) if(result.get(i)==null)
            throw new SubmissionFailure(txs.get(i).wtxId(),new MempoolPolicyException("mempool-full","mempool full",null));
        return result;
    }

    public record SubmissionTxResult(MempoolEntry entry, String error, boolean known,
                                      Long effectiveFeeRate, List<Hash256> effectiveIncludes) {
        public SubmissionTxResult { effectiveIncludes = List.copyOf(effectiveIncludes); }
    }
    public record SubmissionResult(String packageMessage, Map<Hash256, SubmissionTxResult> transactions,
                                    List<Hash256> replacedTransactions) {
        public SubmissionResult {
            transactions = Collections.unmodifiableMap(new LinkedHashMap<>(transactions));
            replacedTransactions = List.copyOf(replacedTransactions);
        }
        public List<MempoolEntry> entries() {
            return transactions.values().stream().map(SubmissionTxResult::entry).filter(Objects::nonNull).toList();
        }
    }
    private static long effectiveRate(long fee, long size) {
        return java.math.BigInteger.valueOf(fee).multiply(java.math.BigInteger.valueOf(1000))
                .divide(java.math.BigInteger.valueOf(size)).longValueExact();
    }
    private static SubmissionTxResult submissionError(MempoolAdmissionException error) {
        String details = error instanceof ProbeScriptFailure script ? script.details
                : error instanceof MempoolFeeException fee ? fee.rpcDetails()
                : error instanceof PackagePolicy.EphemeralSpendFailure ephemeral ? ephemeral.rpcDetails()
                : error instanceof TrucPolicyException truc ? truc.rpcDetails()
                : error instanceof MempoolPolicyException policy ? policy.details()
                : error instanceof MissingInput ? "bad-txns-inputs-missingorspent" : error.getMessage();
        return new SubmissionTxResult(null, details, false, null, List.of());
    }

    public static boolean isSubmissionTopologyAllowed(List<Transaction> transactions) {
        return PackagePolicy.submissionTopologyError(transactions) == null;
    }

    private static boolean reconsiderable(MempoolAdmissionException error) {
        return error instanceof MempoolFeeException || error instanceof MissingInput || switch (error.getMessage()) {
            case "replacement-fee", "replacement-worse-feerate-diagram", "replacement-unchanged-feerate-diagram", "mempool-full" -> true;
            default -> false;
        };
    }

    /** Core RPC submission: individual acceptance is committed even if a later member fails. */
    public synchronized SubmissionResult submitPackage(List<Transaction> transactions, MempoolValidationContext context,
                                                        UtxoView chainUtxos, long clientMaxFeeRate) {
        var txs = List.copyOf(transactions);
        if (clientMaxFeeRate < 0) throw new IllegalArgumentException("Negative client fee rate");
        var results = new LinkedHashMap<Hash256, SubmissionTxResult>();
        try { PackagePolicy.validate(txs, true); }
        catch (MempoolAdmissionException error) {
            for (var tx : txs) results.put(tx.wtxId(), new SubmissionTxResult(null, "package-not-validated", false, null, List.of()));
            return new SubmissionResult(error.getMessage(), results, List.of());
        }
        var before = Set.copyOf(entries.keySet());
        var replacedByRbf = new HashSet<Hash256>();
        var pending = new ArrayList<Transaction>();
        boolean hardFailure = false;
        String message = "success";
        for (var tx : txs) {
            var known = entries.get(tx.txId());
            if (known != null) {
                results.put(tx.wtxId(), new SubmissionTxResult(known, null, true, null, List.of()));
                continue;
            }
            try {
                var entry = admitInternal(tx, context, chainUtxos, false, false, null, null, false, null, clientMaxFeeRate);
                replacedByRbf.addAll(lastReplacedTransactions);
                results.put(tx.wtxId(), new SubmissionTxResult(entry, null, false,
                        effectiveRate(entry.modifiedFee(), entry.virtualSize()), List.of(tx.wtxId())));
            } catch (MempoolAdmissionException error) {
                results.put(tx.wtxId(), submissionError(error));
                if (txs.size() > 1 && reconsiderable(error)) pending.add(tx);
                else { hardFailure = true; message = "transaction failed"; }
            } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException | ru.bitcoin.node.script.ScriptParseException error) {
                results.put(tx.wtxId(), new SubmissionTxResult(null,
                        error instanceof ru.bitcoin.node.consensus.transaction.TransactionValidationException invalid ? invalid.details() : error.getMessage(),
                        false, null, List.of()));
                hardFailure = true; message = "transaction failed";
            }
        }
        if (!hardFailure && !pending.isEmpty()) {
            try {
                var accepted = admitPackageInternal(pending, context, chainUtxos, true, true, clientMaxFeeRate);
                replacedByRbf.addAll(lastReplacedTransactions);
                long fee = 0, size = 0;
                for (var entry : accepted) { fee = Math.addExact(fee, entry.modifiedFee()); size = Math.addExact(size, entry.virtualSize()); }
                var includes = pending.stream().map(Transaction::wtxId).toList();
                long rate = effectiveRate(fee, size);
                for (var entry : accepted) results.put(entry.transaction().wtxId(),
                        new SubmissionTxResult(entry, null, false, rate, includes));
            } catch (MempoolAdmissionException error) {
                message = "transaction failed";
                if (error instanceof SubmissionFailure failure) {
                    message = failure.packageMessage;
                    results.put(failure.wtxid, submissionError(failure.failure));
                } else if (error instanceof SharedPackageFailure) message = error.getMessage();
                else if (error instanceof MempoolFeeException || pending.size() == 1)
                    results.put(pending.getLast().wtxId(), submissionError(error));
                else if (error.getMessage().equals("cluster-count-limit") || error.getMessage().equals("cluster-size-limit"))
                    message = "too-large-cluster";
            }
        }
        for (var tx : txs) {
            var result = results.get(tx.wtxId());
            if (result.entry() != null && !entries.containsKey(tx.txId())) {
                results.put(tx.wtxId(), new SubmissionTxResult(null, "mempool full", false, null, List.of()));
                message = "transaction failed";
            }
        }
        var replaced = replacedByRbf.stream().filter(before::contains).filter(id -> !entries.containsKey(id))
                .sorted(Comparator.comparing(Hash256::toDisplayHex)).toList();
        return new SubmissionResult(message, results, replaced);
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

    public record TestAcceptResult(List<MempoolEntry> entries, String rejectReason, int failedTransactionIndex,
                                   Set<Hash256> knownSubmissionTxids, String rejectDetails) {
        public TestAcceptResult(List<MempoolEntry> entries, String rejectReason, int failedTransactionIndex,
                                Set<Hash256> knownSubmissionTxids) {
            this(entries, rejectReason, failedTransactionIndex, knownSubmissionTxids, null);
        }
        public TestAcceptResult(List<MempoolEntry> entries, String rejectReason, int failedTransactionIndex) {
            this(entries, rejectReason, failedTransactionIndex, Set.of());
        }
        public TestAcceptResult(List<MempoolEntry> entries, String rejectReason) { this(entries, rejectReason, -1); }
        public TestAcceptResult { entries = List.copyOf(entries); knownSubmissionTxids = Set.copyOf(knownSubmissionTxids); }
        public boolean allowed() { return rejectReason == null; }
        /** Package-wide structural rejection, before any individual tx validation. */
        public boolean packagePolicyFailure() {
            if (failedTransactionIndex >= 0) return false;
            return rejectReason != null && (rejectReason.startsWith("TRUC-violation,") || switch (rejectReason) {
                case "package-too-many-transactions", "package-too-large", "package-contains-duplicates",
                        "package-not-sorted", "conflict-in-package", "TRUC-violation", "too-large-cluster" -> true;
                default -> false;
            });
        }
    }

    /**
     * Runs the normal admission path against an isolated clone. No entries, spent map or
     * rolling fee state of this mempool are modified. This is the backing primitive for
     * Core-style testmempoolaccept.
     */
    public synchronized TestAcceptResult testAccept(List<Transaction> transactions,
                                                    MempoolValidationContext context, UtxoView chainUtxos) {
        return testAcceptInternal(transactions, context, chainUtxos, false);
    }

    /** Non-mutating preflight using submitpackage's CPFP and replacement rules. */
    public synchronized TestAcceptResult testSubmitPackage(List<Transaction> transactions,
                                                           MempoolValidationContext context, UtxoView chainUtxos) {
        return testAcceptInternal(transactions, context, chainUtxos, true);
    }

    /** Ordinary broadcast preflight: preserve the original rejection without changing this pool. */
    public synchronized MempoolEntry testBroadcast(Transaction transaction,
                                                  MempoolValidationContext context, UtxoView chainUtxos) {
        return validationClone(false).admit(transaction, context, chainUtxos);
    }

    private Mempool validationClone(boolean scriptDiagnostics) {
        Mempool staged = new Mempool(policy, limits, clock);
        staged.probeScriptDiagnostics = scriptDiagnostics;
        staged.admissionProbe = true;
        staged.copyPoolStateFrom(this);
        staged.feeDeltas.putAll(feeDeltas);
        staged.rollingFee.restoreFrom(rollingFee);
        return staged;
    }

    private TestAcceptResult testAcceptInternal(List<Transaction> transactions,
                                                MempoolValidationContext context, UtxoView chainUtxos,
                                                boolean submission) {
        Objects.requireNonNull(transactions); Objects.requireNonNull(context); Objects.requireNonNull(chainUtxos);
        Mempool staged = validationClone(!submission);
        try {
            if (!submission && transactions.size() > 1) {
                PackagePolicy.validate(transactions, false);
                var accepted = new ArrayList<MempoolEntry>();
                var deferred = new ArrayList<DeferredScript>();
                for (int i = 0; i < transactions.size(); i++) {
                    var tx = transactions.get(i);
                    try {
                        // A probe must validate every supplied transaction; unlike
                        // submitpackage it must not deduplicate known entries.
                        var known = staged.entries.get(tx.txId());
                        if (known != null) throw new MempoolAdmissionException(
                                known.transaction().wtxId().equals(tx.wtxId()) ? "txn-already-in-mempool"
                                        : "txn-same-nonwitness-data-in-mempool");
                        for (var input : tx.inputs())
                            if (staged.spent.containsKey(input.previousOutput()))
                                throw new MempoolAdmissionException("bip125-replacement-disallowed");
                        var entry = staged.admitInternal(tx, context, chainUtxos, true, false, null, null, false, deferred);
                        MempoolGraphPolicy.checkProbeTruc(entries, spent, entry);
                        accepted.add(entry);
                    } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException invalid) {
                        return new TestAcceptResult(List.of(),invalid.reason(),i,Set.of(),invalid.details());
                    } catch (MempoolAdmissionException e) {
                        if (e instanceof TrucPolicyException truc)
                            return new TestAcceptResult(List.of(), "TRUC-violation", i, Set.of(), truc.rpcDetails());
                        if (e instanceof MissingInput) return new TestAcceptResult(List.of(), "missing-inputs", i);
                        return new TestAcceptResult(List.of(), e instanceof MempoolPolicyException policy ? policy.reason() : e.getMessage(), i, Set.of(),
                                e instanceof MempoolFeeException feeError ? feeError.rpcDetails()
                                        : e instanceof MempoolPolicyException policy ? policy.details() : e.getMessage());
                    }
                }
                // Core's shared package checks follow all individual PreChecks.
                try {
                    MempoolGraphPolicy.checkPackageTruc(entries,transactions,staged.entries);
                } catch (MempoolAdmissionException error) {
                    return new TestAcceptResult(List.of(), error instanceof TrucPolicyException truc ? truc.rpcDetails() : "TRUC-violation");
                }
                try {
                    for (var entry : accepted) MempoolGraphPolicy.checkClusterLimits(staged.entries,
                            entry.transaction().txId(), limits);
                } catch (MempoolAdmissionException error) {
                    return new TestAcceptResult(List.of(), "too-large-cluster");
                }
                for (int i = 0; i < transactions.size(); i++) {
                    try { PackagePolicy.ephemeralSpends(transactions.get(i), staged.entries, policy.dustRelaySatPerKvB()); }
                    catch (MempoolAdmissionException error) {
                        return new TestAcceptResult(List.of(), error.getMessage(), i, Set.of(),
                                error instanceof PackagePolicy.EphemeralSpendFailure ephemeral ? ephemeral.rpcDetails() : null);
                    }
                }
                // Core finishes all package PreChecks before executing any scripts.
                // Each check retains the exact input snapshot validated above.
                for (int i = 0; i < deferred.size(); i++) {
                    var check = deferred.get(i);
                    var failure = MempoolValidator.testScripts(check.transaction(), check.coins());
                    if (failure != null) return new TestAcceptResult(accepted.subList(0, i), failure.reason(), i,
                            Set.of(), failure.details());
                }
                return new TestAcceptResult(accepted, null);
            }
            List<MempoolEntry> accepted = transactions.size() == 1 && !submission
                    ? List.of(staged.admit(transactions.getFirst(), context, chainUtxos))
                    : staged.admitPackageInternal(transactions, context, chainUtxos, submission, submission);
            Set<Hash256> known = submission ? transactions.stream().map(Transaction::txId)
                    .filter(entries::containsKey).collect(java.util.stream.Collectors.toSet()) : Set.of();
            return new TestAcceptResult(accepted, null, -1, known);
        } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException invalid) {
            return new TestAcceptResult(List.of(),invalid.reason(),transactions.size()==1?0:-1,Set.of(),invalid.details());
        } catch (MempoolAdmissionException e) {
            if (!submission && e instanceof TrucPolicyException truc) return new TestAcceptResult(List.of(), "TRUC-violation", 0, Set.of(), truc.rpcDetails());
            if (e instanceof ProbeScriptFailure script) return new TestAcceptResult(List.of(), e.getMessage(), 0, Set.of(), script.details);
            if (!submission && e instanceof MissingInput) return new TestAcceptResult(List.of(), "missing-inputs");
            return new TestAcceptResult(List.of(), e instanceof MempoolPolicyException policy ? policy.reason() : e.getMessage(),
                    !submission && transactions.size() == 1 ? 0 : -1, Set.of(),
                    e instanceof MempoolFeeException feeError ? feeError.rpcDetails()
                            : e instanceof PackagePolicy.EphemeralSpendFailure ephemeral ? ephemeral.rpcDetails()
                            : e instanceof MempoolPolicyException policy ? policy.details() : e.getMessage());
        }
    }

    /** Apply a local policy/mining fee delta. The delta is retained even when txid is not yet in mempool. */
    public synchronized long prioritise(Hash256 txId, long feeDelta) {
        Objects.requireNonNull(txId, "txId");
        MempoolEntry existing = entries.get(txId);
        if (existing != null && existing.transaction().outputs().stream().anyMatch(out ->
                out.value() < ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.dustThreshold(out, policy.dustRelaySatPerKvB())))
            throw new MempoolAdmissionException("Priority is not supported for transactions with dust outputs.");
        long updated = Math.addExact(feeDeltas.getOrDefault(txId, 0L), feeDelta);
        if (updated == 0) feeDeltas.remove(txId); else feeDeltas.put(txId, updated);
        MempoolEntry current = entries.get(txId);
        if (current != null && current.feeDelta() != updated) {
            entries.put(txId, current.withFeeDelta(updated)); graphIndex.entryChanged(txId);
        }
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
        if (current != null && current.feeDelta() != feeDelta) {
            entries.put(txId, current.withFeeDelta(feeDelta)); graphIndex.entryChanged(txId);
        }
    }

    public synchronized boolean contains(Hash256 txId) { return entries.containsKey(Objects.requireNonNull(txId)); }
    public synchronized Optional<MempoolEntry> find(Hash256 txId) { return Optional.ofNullable(entries.get(Objects.requireNonNull(txId))); }

    /** Eviction removes descendants as their unconfirmed inputs would otherwise disappear. */
    public synchronized Optional<MempoolEntry> remove(Hash256 txId) {
        Objects.requireNonNull(txId);
        MempoolEntry root = entries.get(txId);
        if (root == null) return Optional.empty();
        Set<Hash256> removed = graphIndex.descendants(txId);
        randomizedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size(), removed.size(), 0);
        for (Hash256 id : removed) {
            MempoolEntry entry = entries.remove(id);
            arrivalIndex.remove(new Arrival(entry.arrivalTime(),id));
            totalEntryMemory = Math.subtractExact(totalEntryMemory, CoreMemoryUsage.entry(entry));
            totalVirtualBytes = Math.subtractExact(totalVirtualBytes, entry.virtualSize());
            for (var input : entry.transaction().inputs()) spent.remove(input.previousOutput(), id);
        }
        unbroadcast.retainAll(entries.keySet());
        graphIndex.removeAll(removed);
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
        return revalidateInternal(context, chainUtxos, confirmed, true);
    }
    private List<Hash256> revalidateInternal(MempoolValidationContext context, UtxoView chainUtxos,
                                             Set<Hash256> confirmed, boolean optimize) {
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
        randomizedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size(),
                entries.size() - replacement.entries.size(), 0);
        entries.clear();
        entries.putAll(replacement.entries);
        arrivalIndex.clear();
        entries.forEach((id,value)->arrivalIndex.add(new Arrival(value.arrivalTime(),id)));
        totalEntryMemory = replacement.totalEntryMemory;
        unbroadcast.retainAll(entries.keySet());
        spent.clear();
        spent.putAll(replacement.spent);
        graphIndex.reconcile(entries, spent);
        totalVirtualBytes = entries.values().stream().mapToLong(MempoolEntry::virtualSize).sum();
        if (optimize) finishGraphChange();
        rollingFee.blockConnected(clock.instant().getEpochSecond());
        if (!removed.isEmpty()) sequence++;
        return List.copyOf(removed);
    }
    /** Chain reconciliation, including disconnected transactions, commits as one operation. */
    public synchronized void reconcile(MempoolValidationContext context, UtxoView chainUtxos,
                                       Set<Hash256> confirmed, List<Transaction> disconnected) {
        Set<Hash256> beforeIds = Set.copyOf(entries.keySet());
        Mempool staged = new Mempool(policy, limits, clock);
        staged.copyPoolStateFrom(this);
        staged.feeDeltas.putAll(feeDeltas);
        staged.unbroadcast.addAll(unbroadcast);
        staged.removeSeedEntries(confirmed);
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
        // Only insertion order changed; spent, byte accounting and graph are still valid.
        staged.revalidateInternal(context, chainUtxos, confirmed, false);
        trimReorgClusters(staged);
        staged.finishGraphChange();
        staged.expire();
        int beforeTrim = staged.size();
        var memoryTrim = staged.trimToMemory(staged.entries, null);
        long removedRate = memoryTrim.removedRate();
        commitEntries(staged.entries, memoryTrim.graph(), false);
        randomizedCapacity = memoryTrim.capacity();
        unbroadcast.clear(); unbroadcast.addAll(staged.unbroadcast); unbroadcast.retainAll(entries.keySet());
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
        Set<Hash256> ids = new LinkedHashSet<>(graphIndex.ancestors(txId));
        ids.remove(txId);
        return Optional.of(List.copyOf(ids));
    }

    /** In-mempool descendants excluding the transaction itself. */
    public synchronized Optional<List<Hash256>> descendants(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        if (!entries.containsKey(txId)) return Optional.empty();
        Set<Hash256> ids = new LinkedHashSet<>(graphIndex.descendants(txId));
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
        Map<Hash256, ClusterLinearization.Chunk> chunks = new HashMap<>();
        for (Hash256 txId : txIds) {
            EntryGraphView view = graphView(txId, chunks).orElse(null);
            if (view != null) result.put(txId, view);
        }
        return Map.copyOf(result);
    }

    /** Atomic Core-v31-style view of one connected mempool cluster. */
    public synchronized Optional<ClusterView> cluster(Hash256 txId) {
        Objects.requireNonNull(txId, "txId");
        if (!entries.containsKey(txId)) return Optional.empty();
        Set<Hash256> ids = graphIndex.component(txId);
        List<ClusterLinearization.Chunk> chunks = graphIndex.chunks(entries, txId);
        long adjustedWeight = ids.stream().mapToLong(id -> entries.get(id).adjustedWeight()).sum();
        return Optional.of(new ClusterView(adjustedWeight, ids.size(), chunks));
    }

    /** Atomic whole-mempool feerate diagram, in descending mining order. */
    public synchronized List<ClusterLinearization.Chunk> feeRateDiagram() {
        return ClusterLinearization.mergeChunks(graphIndex.linearizations(entries));
    }

    /** Atomic immutable entries and matching cached cluster order, safe after releasing the monitor. */
    public synchronized MiningSnapshot miningSnapshot() {
        return new MiningSnapshot(List.copyOf(entries.values()), graphIndex.linearizations(entries));
    }

    public static final class MiningSnapshot {
        private final List<MempoolEntry> entries;
        private final List<List<ClusterLinearization.Chunk>> clusters;
        private MiningSnapshot(List<MempoolEntry> entries, List<List<ClusterLinearization.Chunk>> clusters) {
            this.entries = entries; this.clusters = clusters;
        }
        public List<MempoolEntry> entries() { return entries; }
        public List<List<ClusterLinearization.Chunk>> clusters() { return clusters; }
    }

    /** Atomic graph data used by getmempoolentry. Sets include the transaction itself. */
    public synchronized Optional<EntryGraphView> graphView(Hash256 txId) {
        return graphView(txId, new HashMap<>());
    }

    /** Chunks live only for this atomic query; solve each requested component once. */
    private Optional<EntryGraphView> graphView(Hash256 txId, Map<Hash256, ClusterLinearization.Chunk> chunks) {
        Objects.requireNonNull(txId, "txId");
        MempoolEntry entry = entries.get(txId);
        if (entry == null) return Optional.empty();
        Set<Hash256> ancestors = graphIndex.ancestors(txId);
        Set<Hash256> descendants = graphIndex.descendants(txId);
        Set<Hash256> parents = graphIndex.parents(txId);
        Set<Hash256> children = graphIndex.children(txId);
        var chunk = chunks.get(txId);
        if (chunk == null) {
            for (var value : graphIndex.chunks(entries, txId))
                for (var member : value.transactions()) chunks.put(member, value);
            chunk = Objects.requireNonNull(chunks.get(txId), "Missing graph chunk");
        }
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
        while (!arrivalIndex.isEmpty() && arrivalIndex.first().time()<cutoff) {
            var oldest=arrivalIndex.first();
            if(remove(oldest.id()).isEmpty()) arrivalIndex.remove(oldest);
        }
        return before - entries.size();
    }

    /** Caller holds the source monitor; destination is an unpublished staging pool. */
    private void copyPoolStateFrom(Mempool source) {
        entries.putAll(source.entries);
        spent.putAll(source.spent);
        totalVirtualBytes = source.totalVirtualBytes;
        randomizedCapacity = source.randomizedCapacity;
        totalEntryMemory = source.totalEntryMemory;
        arrivalIndex.addAll(source.arrivalIndex);
        graphIndex.copyFrom(source.graphIndex);
    }

    /** Seed filtering must retain children of confirmed parents; this is not descendant eviction. */
    private void removeSeedEntries(Collection<Hash256> ids) {
        for (var id : ids) {
            var entry = entries.remove(id);
            if (entry == null) continue;
            arrivalIndex.remove(new Arrival(entry.arrivalTime(),id));
            totalEntryMemory = Math.subtractExact(totalEntryMemory, CoreMemoryUsage.entry(entry));
            randomizedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size() + 1, 1, 0);
            totalVirtualBytes = Math.subtractExact(totalVirtualBytes, entry.virtualSize());
            for (var input : entry.transaction().inputs()) spent.remove(input.previousOutput(), id);
        }
        graphIndex.removeAll(ids);
    }

    /** Publish an already validated candidate. Read inputs only for changed txids. */
    private void commitEntries(Map<Hash256, MempoolEntry> candidate) {
        commitEntries(candidate, null, true);
    }
    private void commitEntries(Map<Hash256, MempoolEntry> candidate, MempoolGraphIndex stagedGraph, boolean optimize) {
        var removed = new ArrayList<MempoolEntry>();
        var added = new ArrayList<MempoolEntry>();
        var changedScores = new ArrayList<Hash256>();
        long proposedBytes = totalVirtualBytes;
        long proposedMemory = totalEntryMemory;
        for (var item : entries.entrySet()) {
            if (!candidate.containsKey(item.getKey())) {
                removed.add(item.getValue());
                proposedMemory = Math.subtractExact(proposedMemory, CoreMemoryUsage.entry(item.getValue()));
                proposedBytes = Math.subtractExact(proposedBytes, item.getValue().virtualSize());
            }
        }
        for (var item : candidate.entrySet()) {
            var previous = entries.get(item.getKey());
            if (previous == null) {
                added.add(item.getValue());
                proposedMemory = Math.addExact(proposedMemory, CoreMemoryUsage.entry(item.getValue()));
                proposedBytes = Math.addExact(proposedBytes, item.getValue().virtualSize());
            } else {
                if (previous.modifiedFee() != item.getValue().modifiedFee()
                        || previous.adjustedWeight() != item.getValue().adjustedWeight()) changedScores.add(item.getKey());
                // Same txid can carry updated sigops/weight metadata after revalidation.
                proposedBytes = Math.addExact(Math.subtractExact(proposedBytes, previous.virtualSize()), item.getValue().virtualSize());
            }
        }
        // Arithmetic checks above precede mutation. Remove old spenders before adding replacements.
        for(var entry : removed) arrivalIndex.remove(new Arrival(entry.arrivalTime(),entry.transaction().txId()));
        for(var item : candidate.entrySet()) {
            var old=entries.get(item.getKey());
            if(old==null || old.arrivalTime()!=item.getValue().arrivalTime()) {
                if(old!=null) arrivalIndex.remove(new Arrival(old.arrivalTime(),item.getKey()));
                arrivalIndex.add(new Arrival(item.getValue().arrivalTime(),item.getKey()));
            }
        }
        for (var entry : removed)
            for (var input : entry.transaction().inputs()) spent.remove(input.previousOutput(), entry.transaction().txId());
        for (var entry : added)
            for (var input : entry.transaction().inputs()) spent.put(input.previousOutput(), entry.transaction().txId());
        randomizedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size(), removed.size(), added.size());
        entries.clear(); entries.putAll(candidate);
        totalVirtualBytes = proposedBytes;
        totalEntryMemory = proposedMemory;
        unbroadcast.retainAll(entries.keySet());
        if (stagedGraph == null) {
            graphIndex.reconcile(entries, spent);
            changedScores.forEach(graphIndex::entryChanged);
        } else {
            // Preserve staging's linearizations/quality when committing. Trimming
            // may have removed entries since staging's last graph update.
            stagedGraph.reconcile(entries, spent);
            graphIndex.copyFrom(stagedGraph);
        }
        if (optimize) finishGraphChange();
    }

    /** Core allocator estimate. This is deliberately separate from BIP141/policy vsize. */
    public synchronized long dynamicMemoryUsage() {
        return totalEntryMemory
                + CoreMemoryUsage.allocation(40 * randomizedCapacity) + 96L * feeDeltas.size()
                + graphIndex.memoryUsage(entries);
    }

    private record MemoryTrim(long removedRate, long capacity, MempoolGraphIndex graph, int beforeTrim) { }
    private record ReplacementDiagram(int comparison, MempoolGraphIndex graph) { }
    private ReplacementDiagram replacementDiagrams(Map<Hash256,MempoolEntry> after, Set<Hash256> conflicts,
                                                    Collection<Transaction> replacements, MempoolGraphIndex prospective) {
        var roots=new LinkedHashSet<>(conflicts);
        for(var tx : replacements) for(var input : tx.inputs()) roots.add(input.previousOutput().transactionId());
        var oldMembers=new LinkedHashSet<Hash256>();
        for(var root : roots) oldMembers.addAll(graphIndex.component(root));
        var oldChunks=affectedChunks(graphIndex,entries,oldMembers);
        if(prospective==null) {
            prospective=new MempoolGraphIndex(); prospective.copyFrom(graphIndex);
            var nextSpent=new HashMap<>(spent);
            for(var item : entries.entrySet()) if(!after.containsKey(item.getKey()))
                for(var input : item.getValue().transaction().inputs()) nextSpent.remove(input.previousOutput(),item.getKey());
            for(var item : after.entrySet()) if(!entries.containsKey(item.getKey()))
                for(var input : item.getValue().transaction().inputs()) nextSpent.put(input.previousOutput(),item.getKey());
            prospective.reconcile(after,nextSpent);
        }
        var newRoots=new LinkedHashSet<>(oldMembers);
        replacements.forEach(tx -> newRoots.add(tx.txId()));
        var newChunks=affectedChunks(prospective,after,newRoots);
        return new ReplacementDiagram(ClusterLinearization.compareDiagrams(newChunks,oldChunks),prospective);
    }
    static List<ClusterLinearization.Chunk> affectedChunks(MempoolGraphIndex graph,
            Map<Hash256,MempoolEntry> values, Collection<Hash256> roots) {
        var seen=new HashSet<Hash256>(); var groups=new ArrayList<List<ClusterLinearization.Chunk>>();
        for(var root : roots) {
            if(seen.contains(root) || !values.containsKey(root)) continue;
            var component=graph.component(root);
            if(component.isEmpty()) continue;
            seen.addAll(component); groups.add(graph.chunks(values,root));
        }
        return ClusterLinearization.mergeChunks(groups);
    }
    /** Reorg late-parent links can join formerly independent, valid clusters. */
    private void trimReorgClusters(Mempool staged) {
        long weightLimit = Math.multiplyExact(limits.clusterVirtualBytes(),4);
        boolean oversized = staged.graphIndex.components().stream().anyMatch(group -> group.size()>limits.clusterCount()
                || group.stream().mapToLong(id -> staged.entries.get(id).adjustedWeight()).sum()>weightLimit);
        if (!oversized) return;
        var retained = new LinkedHashMap<Hash256,MempoolEntry>();
        var resurrected = new LinkedHashMap<Hash256,MempoolEntry>();
        for (var item : staged.entries.entrySet()) {
            if (entries.containsKey(item.getKey())) retained.put(item.getKey(),item.getValue());
            else resurrected.put(item.getKey(),item.getValue());
        }
        var oldGraph = new MempoolGraphIndex(); oldGraph.copyFrom(graphIndex);
        var gone = new HashSet<>(entries.keySet()); gone.removeAll(retained.keySet()); oldGraph.removeAll(gone);
        var orders = new ArrayList<>(oldGraph.linearizations(retained));
        for (var group : ClusterLinearization.clusters(resurrected))
            orders.add(ClusterLinearization.chunks(resurrected,group));
        var dependencies = new ArrayList<ClusterLimitTrimmer.Dependency>();
        for (var item : staged.entries.entrySet()) for (var input : item.getValue().transaction().inputs()) {
            var parent=input.previousOutput().transactionId();
            if (staged.entries.containsKey(parent)) dependencies.add(new ClusterLimitTrimmer.Dependency(parent,item.getKey()));
        }
        staged.removeSeedEntries(ClusterLimitTrimmer.removed(staged.entries,orders,dependencies,
                limits.clusterCount(),weightLimit));
    }
    private MemoryTrim trimToMemory(Map<Hash256, MempoolEntry> candidate, Hash256 added) {
        return trimToMemory(candidate,added,graphIndex,capacityForCandidate(candidate));
    }
    private long capacityForCandidate(Map<Hash256,MempoolEntry> candidate) {
        int removed = (int)entries.keySet().stream().filter(id -> !candidate.containsKey(id)).count();
        int inserted = (int)candidate.keySet().stream().filter(id -> !entries.containsKey(id)).count();
        return CoreMemoryUsage.randomizedCapacity(randomizedCapacity,entries.size(),removed,inserted);
    }
    private void expireCandidate(Map<Hash256,MempoolEntry> candidate) {
        long cutoff=clock.instant().getEpochSecond()-limits.expirySeconds();
        var expired=new HashSet<Hash256>();
        for(var item : candidate.entrySet()) if(item.getValue().arrivalTime()<cutoff) expired.add(item.getKey());
        if(!expired.isEmpty()) MempoolGraphPolicy.descendants(candidate,expired).forEach(candidate::remove);
    }
    private MempoolGraphIndex candidateGraph(Map<Hash256,MempoolEntry> candidate,MempoolGraphIndex base) {
        var graph=new MempoolGraphIndex(); graph.copyFrom(base);
        var prospectiveSpent=new HashMap<>(spent);
        for(var item : entries.entrySet()) if(!candidate.containsKey(item.getKey()))
            for(var input : item.getValue().transaction().inputs()) prospectiveSpent.remove(input.previousOutput(),item.getKey());
        for(var item : candidate.entrySet()) if(!entries.containsKey(item.getKey()))
            for(var input : item.getValue().transaction().inputs()) prospectiveSpent.put(input.previousOutput(),item.getKey());
        graph.reconcile(candidate,prospectiveSpent);
        if(base==graphIndex) for(var item : candidate.entrySet()) {
            var old=entries.get(item.getKey());
            if(old!=null && (old.modifiedFee()!=item.getValue().modifiedFee() || old.adjustedWeight()!=item.getValue().adjustedWeight()))
                graph.entryChanged(item.getKey());
        }
        return graph;
    }
    private MemoryTrim trimToMemory(Map<Hash256,MempoolEntry> candidate,Hash256 added,MempoolGraphIndex base,long initialCapacity) {
        int beforeTrim=candidate.size();
        long[] capacity = {initialCapacity};
        long inner = totalEntryMemory;
        for (var item : entries.entrySet()) if (!candidate.containsKey(item.getKey()))
            inner = Math.subtractExact(inner, CoreMemoryUsage.entry(item.getValue()));
        for (var item : candidate.entrySet()) {
            var old = entries.get(item.getKey());
            if (old == item.getValue()) continue;
            if (old != null) inner = Math.subtractExact(inner, CoreMemoryUsage.entry(old));
            inner = Math.addExact(inner, CoreMemoryUsage.entry(item.getValue()));
        }
        if (inner + CoreMemoryUsage.allocation(40 * capacity[0]) + 96L * feeDeltas.size()
                + 400L * candidate.size() <= limits.maxPoolMemoryBytes()) return new MemoryTrim(0, capacity[0],base,beforeTrim);
        var previous = new LinkedHashSet<>(candidate.keySet());
        var graph = candidateGraph(candidate,base);
        java.util.function.LongSupplier usage = () -> {
            var deleted = new HashSet<>(previous); deleted.removeAll(candidate.keySet());
            if (!deleted.isEmpty()) {
                capacity[0] = CoreMemoryUsage.randomizedCapacity(capacity[0], previous.size(), deleted.size(), 0);
                graph.removeAll(deleted); previous.removeAll(deleted);
            }
            return candidate.values().stream().mapToLong(CoreMemoryUsage::entry).sum()
                    + CoreMemoryUsage.allocation(40 * capacity[0]) + 96L * feeDeltas.size()
                    + graph.memoryUsage(candidate);
        };
        long rate = MempoolGraphPolicy.trim(candidate, limits.maxPoolMemoryBytes(), added, usage,
                () -> graph.linearizations(candidate));
        // Update retained capacity even when the last removal emptied the pool.
        usage.getAsLong();
        return new MemoryTrim(rate, capacity[0],graph,beforeTrim);
    }

    private void finishGraphChange() { graphIndex.doWork(entries, ClusterOptimization.POST_CHANGE_COST); }
    public synchronized boolean optimize(long budget) { return graphIndex.doWork(entries, budget); }
    public synchronized boolean optimal() { return graphIndex.optimal(); }

    private void indexAddition(MempoolEntry entry) {
        arrivalIndex.add(new Arrival(entry.arrivalTime(),entry.transaction().txId()));
        totalEntryMemory = Math.addExact(totalEntryMemory, CoreMemoryUsage.entry(entry));
        randomizedCapacity = CoreMemoryUsage.randomizedCapacity(randomizedCapacity, entries.size() - 1, 0, 1);
        var transaction = entry.transaction(); var id = transaction.txId();
        var parents = new LinkedHashSet<Hash256>(); var children = new LinkedHashSet<Hash256>();
        for (var input : transaction.inputs()) {
            var parent = input.previousOutput().transactionId();
            if (!parent.equals(id) && entries.containsKey(parent)) parents.add(parent);
        }
        for (int index = 0; index < transaction.outputs().size(); index++) {
            var child = spent.get(new OutPoint(id, new ru.bitcoin.node.common.types.UInt32(index)));
            if (child != null && !child.equals(id)) children.add(child);
        }
        graphIndex.add(id, parents, children);
    }
    public synchronized long minimumFeeRate() {
        return rollingFee.get(clock.instant().getEpochSecond(), this::dynamicMemoryUsage,
                limits.maxPoolMemoryBytes(), limits.incrementalRelaySatPerKvB());
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
