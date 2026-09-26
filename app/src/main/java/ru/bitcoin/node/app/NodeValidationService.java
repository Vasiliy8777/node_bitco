package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.mempool.RocksDbMempoolStore;
import ru.bitcoin.node.storage.mempool.PersistedMempoolEntry;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.util.*;

/**
 * Application entry point for block and transaction admission. The caller owns
 * the database lifetime and must not mutate these stores through another processor.
 */
public final class NodeValidationService {
    private final ChainState chain;
    private final BlockIndexLookup lookup;
    private final RocksDbBlockStore blocks;
    private final UtxoView coins;
    private final BlockProcessor processor;
    private final Mempool mempool;
    private final RocksDbMempoolStore mempoolStore;
    private final boolean persistMempool;
    private final NetworkParameters parameters;
    private final AdjustedTime time;
    private final RocksDbUtxoStore utxos;
    private final BlockPruner blockPruner;
    private final RocksDbPruneStateStore pruneState;
    private final long pruneTargetBytes;
    private BlockIndex poolTip;
    private long revision;
    private final ActiveChainAncestors activeAncestors = new ActiveChainAncestors();
    private final InitialBlockDownloadState initialBlockDownload;

    public long revision() {
        synchronized (chain) {
            synchronizePool();
            return revision;
        }
    }

    public record MiningSnapshot(Block block, List<MempoolEntry> entries, long height, long medianTimePast,
                                 long revision) {
    }

    public MiningSnapshot miningSnapshot(byte[] payout, byte[] extraNonce, long weight, FeeRate feeRate) {
        synchronized (chain) {
            Block block = createMiningTemplate(payout, extraNonce, weight, feeRate);
            return new MiningSnapshot(block, mempool.entries(), chain.activeTip().height() + 1,
                    MedianTimePast.calculate(chain.activeTip(), lookup), revision);
        }
    }

    public void awaitRevision(long previous, long timeoutMillis) throws InterruptedException {
        synchronized (chain) {
            if (revision == previous) chain.wait(timeoutMillis);
        }
    }


    /** Snapshot of local pruning state for RPC/P2P policy. */
    public PruneInfo pruneInfo() {
        synchronized (chain) {
            boolean hasPruned = pruneState.hasPruned();
            long pruneHeight = hasPruned ? lowestAvailableActiveHeight() : 0L;
            return new PruneInfo(blockPruner.enabled(), hasPruned, pruneHeight, pruneTargetBytes);
        }
    }

    /** True only when the hash is an active-chain block whose raw body was removed by pruning. */
    public boolean isPrunedActiveBlock(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            if (!pruneState.hasPruned()) return false;
            BlockIndex candidate = lookup.find(hash);
            if (candidate == null || candidate.height() > chain.activeTip().height()) return false;
            BlockIndex active = activeAncestors.at(chain.activeTip(), candidate.height(), lookup);
            return active != null && active.hash().equals(hash) && blocks.find(hash).isEmpty();
        }
    }

    private long lowestAvailableActiveHeight() {
        BlockIndex tip = chain.activeTip();
        long highestPruned = pruneState.highestPrunedHeight().orElse(-1L);
        long upper = Math.min(highestPruned + 1L, tip.height());
        for (long height = upper; height >= 0; height--) {
            BlockIndex index = activeAncestors.at(tip, height, lookup);
            if (index != null && blocks.find(index.hash()).isEmpty()) return height + 1L;
        }
        return 0L;
    }

    public record PruneInfo(boolean enabled, boolean hasPruned, long pruneHeight, long targetBytes) {}

    public Optional<Block> findBlock(Hash256 hash) {
        synchronized (chain) {
            var candidate = lookup.find(hash);
            if (candidate == null) return Optional.empty();
            var cursor = activeAncestors.at(chain.activeTip(), candidate.height(), lookup);
            return cursor != null && cursor.hash().equals(hash) ? blocks.find(hash) : Optional.empty();
        }
    }

    /**
     * Returns active-chain depth: tip=0, parent=1; empty for unknown/side-chain blocks.
     */
    public OptionalLong activeBlockDepth(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            BlockIndex candidate = lookup.find(hash);
            if (candidate == null) return OptionalLong.empty();
            BlockIndex tip = chain.activeTip();
            if (candidate.height() > tip.height()) return OptionalLong.empty();
            BlockIndex cursor = activeAncestors.at(tip, candidate.height(), lookup);
            if (!cursor.hash().equals(hash)) return OptionalLong.empty();
            return OptionalLong.of(tip.height() - candidate.height());
        }
    }

    /**
     * Only returns active-chain headers, in forward order, bounded to the wire limit.
     */
    public List<ru.bitcoin.node.protocol.block.BlockHeader> headers(List<Hash256> locator, Hash256 stop) {
        synchronized (chain) {
            BlockIndex tip = chain.activeTip();
            long start = bestActiveLocator(locator).map(lookup::find).map(BlockIndex::height).orElse(0L);
            var answer = new ArrayList<ru.bitcoin.node.protocol.block.BlockHeader>();
            long count = Math.min(2000, tip.height() - start);
            // Warm the upper end once; successive heights then reuse nearby cached ancestors.
            if (count > 0) activeAncestors.at(tip, start + count, lookup);
            for (long offset = 1; offset <= count; offset++) {
                var header = activeAncestors.at(tip, start + offset, lookup).header();
                answer.add(header);
                if (header.hash().equals(stop)) break;
            }
            return List.copyOf(answer);
        }
    }

    /**
     * Returns the best active-chain block referenced by a peer locator, or empty when none matches.
     */
    public Optional<Hash256> bestActiveLocator(List<Hash256> locator) {
        Objects.requireNonNull(locator, "locator");
        synchronized (chain) {
            if (locator.isEmpty()) return Optional.empty();
            BlockIndex tip = chain.activeTip();
            BlockIndex best = null;
            for (Hash256 hash : locator) {
                BlockIndex candidate = lookup.find(hash);
                if (candidate == null || candidate.height() > tip.height()
                        || (best != null && candidate.height() <= best.height())) continue;
                BlockIndex active = activeAncestors.at(tip, candidate.height(), lookup);
                if (active.hash().equals(hash)) best = candidate;
            }
            return best == null ? Optional.empty() : Optional.of(best.hash());
        }
    }

    /**
     * Returns headers strictly after {@code knownHash} through {@code tipHash} when both are on the
     * active chain and the path is contiguous and no longer than {@code maxHeaders}. An empty
     * Optional means a headers announcement cannot safely connect and the caller should fall back
     * to INV. An empty list means the peer already knows {@code tipHash}.
     */
    public Optional<List<ru.bitcoin.node.protocol.block.BlockHeader>> activeHeadersAfter(
            Hash256 knownHash,
            Hash256 tipHash,
            int maxHeaders
    ) {
        Objects.requireNonNull(knownHash, "knownHash");
        Objects.requireNonNull(tipHash, "tipHash");
        if (maxHeaders <= 0) throw new IllegalArgumentException("maxHeaders must be positive");
        synchronized (chain) {
            BlockIndex known = lookup.find(knownHash);
            BlockIndex tip = lookup.find(tipHash);
            if (known == null || tip == null || known.height() > tip.height()) return Optional.empty();

            BlockIndex activeAtTipHeight = activeAncestors.at(chain.activeTip(), tip.height(), lookup);
            if (activeAtTipHeight == null || !activeAtTipHeight.hash().equals(tipHash)) return Optional.empty();

            long distance = tip.height() - known.height();
            if (distance > maxHeaders) return Optional.empty();

            ArrayDeque<ru.bitcoin.node.protocol.block.BlockHeader> result = new ArrayDeque<>();
            BlockIndex cursor = tip;
            while (cursor.height() > known.height()) {
                result.addFirst(cursor.header());
                cursor = Objects.requireNonNull(lookup.find(cursor.previousBlockHash()), "Missing active ancestor");
            }
            if (!cursor.hash().equals(knownHash)) return Optional.empty();
            return Optional.of(List.copyOf(result));
        }
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool) {
        this(database, parameters, time, mempool, 0L, parameters.defaultAssumeValid(), true);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes) {
        this(database, parameters, time, mempool, pruneTargetBytes, parameters.defaultAssumeValid(), true);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes, Hash256 assumedValidBlock) {
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, true);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool) {
        this.mempool = Objects.requireNonNull(mempool);
        this.mempoolStore = new RocksDbMempoolStore(database);
        this.persistMempool = persistMempool;
        this.blockPruner = new BlockPruner(database, pruneTargetBytes);
        this.pruneState = new RocksDbPruneStateStore(database);
        this.pruneTargetBytes = pruneTargetBytes;
        this.parameters = Objects.requireNonNull(parameters);
        this.time = Objects.requireNonNull(time);
        utxos = new RocksDbUtxoStore(database);
        var indexes = new RocksDbBlockIndexStore(database);
        var failures =
                new RocksDbBlockFailureStore(
                        database
                );
        var tips = new RocksDbChainStateStore(database);
        var undos = new RocksDbUndoStore(database);
        blocks = new RocksDbBlockStore(database);
        lookup = new StoredBlockIndexLookup(indexes);
        var failureResolver =
                new BlockFailureResolver(
                        lookup,
                        failures
                );

        var failureManager =
                new BlockFailureManager(
                        database,
                        failures,
                        indexes,
                        tips,
                        failureResolver
                );
        chain = new ChainInitializer(database, parameters).initialize();
        initialBlockDownload = new InitialBlockDownloadState(parameters, time::currentTimeSeconds);
        var storage = new RocksDbChainTransitionStorage(database, utxos, undos, indexes, tips);
        var executor = new ChainReorganizationExecutor(
                blocks,
                undos,
                utxos,
                new ChainTransitionManager(chain, storage),
                parameters,
                lookup,
                failureManager::markFailed,
                new AssumeValidPolicy(
                        lookup,
                        () -> tips.loadBestHeaderTipHash().map(lookup::find).orElse(null),
                        parameters,
                        Objects.requireNonNull(assumedValidBlock, "assumedValidBlock")
                )
        );
        processor =
                new BlockProcessor(
                        chain,
                        lookup,
                        new KnownBlockStorage(
                                database,
                                blocks,
                                indexes
                        ),
                        executor,
                        parameters,
                        time,
                        failureManager,
                        failureResolver
                );
        coins = point -> utxos.find(point).map(coin -> new UtxoEntry(coin.amount(), coin.scriptPubKey(), coin.height(), coin.coinbase()));
        poolTip = chain.activeTip();
        synchronized (chain) {
            if (persistMempool) restorePersistentMempool();
            mempool.revalidate(context(), coins, Set.of());
            mempool.expire();
            if (persistMempool) mempoolStore.replace(persistedMempoolEntries());
            blockPruner.prune(chain.activeTip());
            initialBlockDownload.update(chain.activeTip());
        }
    }

    public BlockProcessingResult processBlock(Block block) {
        synchronized (chain) {
            synchronizePool();
            BlockProcessingResult result = processor.process(block);
            // Chain storage has committed. On a history/storage failure poolTip stays old,
            // and every later API call retries synchronization before exposing the pool.
            synchronizePool();
            if (result == BlockProcessingResult.CONNECTED) {
                blockPruner.prune(chain.activeTip());
                initialBlockDownload.update(chain.activeTip());
            }
            return result;
        }
    }

    public MempoolEntry admit(Transaction transaction) {
        synchronized (chain) {
            synchronizePool();
            var before = mempool.entries();
            try {
                mempool.expire();
                var entry = mempool.admit(transaction, context(), coins);
                revision++;
                chain.notifyAll();
                return entry;
            } finally {
                if (persistMempool) mempoolStore.apply(persistedEntries(before), persistedMempoolEntries());
            }
        }
    }

    public List<MempoolEntry> mempoolEntries() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.entries();
        }
    }

    /**
     * Current local BIP133 fee-filter floor in sat/kvB, before privacy
     * quantization performed by the relay layer.
     */
    public long feeFilterRate() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.feeFilterRate();
        }
    }

    public long minimumRelayFeeRate() {
        synchronized (chain) {
            return mempool.minimumRelayFeeRate();
        }
    }

    public List<MempoolEntry> admitPackage(List<Transaction> transactions) {
        synchronized (chain) {
            synchronizePool();
            var before = mempool.entries();
            try {
                mempool.expire();
                var entries = mempool.admitPackage(transactions, context(), coins);
                revision++;
                chain.notifyAll();
                return entries;
            } finally {
                if (persistMempool) mempoolStore.apply(persistedEntries(before), persistedMempoolEntries());
            }
        }
    }

    /** Bitcoin Core-style latched Initial Block Download state. */
    public boolean isInitialBlockDownload() {
        synchronized (chain) {
            initialBlockDownload.update(chain.activeTip());
            return initialBlockDownload.isInitialBlockDownload();
        }
    }

    public BlockIndex activeTip() {
        synchronized (chain) {
            return chain.activeTip();
        }
    }

    /**
     * Fresh template from one coherent chain/mempool snapshot. Does not search PoW.
     */
    public Block createMiningTemplate(byte[] payout, byte[] extraNonce, long maximumWeight, FeeRate minimumRate) {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            var parent = chain.activeTip();
            long now = time.currentTimeSeconds();
            long timestamp = Math.max(now, Math.addExact(MedianTimePast.calculate(parent, lookup), 1));
            if (timestamp > Math.addExact(now, 7200))
                throw new IllegalStateException("Chain time too far ahead of local time");
            var blockTime = new ru.bitcoin.node.common.types.UInt32(timestamp);
            var bits = ChainHeaderValidator.nextBits(parent, lookup, parameters, blockTime);
            return ru.bitcoin.node.mining.BlockTemplateBuilder.fromMempool(parent, lookup, utxos, parameters,
                    0x20000000, blockTime, bits, payout, extraNonce, mempool.entries(), maximumWeight, minimumRate);
        }
    }

    private MempoolValidationContext context() {
        BlockIndex tip = chain.activeTip();
        var resolver = new BlockIndexMedianTimePastResolver(tip, lookup);
        return new MempoolValidationContext(Math.addExact(tip.height(), 1), MedianTimePast.calculate(tip, lookup),
                resolver::resolvePreviousMedianTimePast);
    }

    private void synchronizePool() {
        BlockIndex tip = chain.activeTip();
        if (tip.hash().equals(poolTip.hash())) return;
        var plan = ReorganizationPlanner.plan(poolTip, tip, lookup);
        Set<Hash256> confirmed = new HashSet<>();
        for (var index : plan.blocksToConnect()) {
            for (var tx : requireBlock(index).transactions()) confirmed.add(tx.txId());
        }
        List<Transaction> retry = new ArrayList<>();
        for (var index : plan.blocksToDisconnect().reversed()) {
            for (var tx : requireBlock(index).transactions()) if (!tx.isCoinbase()) retry.add(tx);
        }
        var before = mempool.entries();
        mempool.reconcile(context(), coins, confirmed, retry);
        if (persistMempool) mempoolStore.apply(persistedEntries(before), persistedMempoolEntries());
        poolTip = tip;
        revision++;
        chain.notifyAll();
    }


    private List<PersistedMempoolEntry> persistedMempoolEntries() {
        return persistedEntries(mempool.entries());
    }

    private static List<PersistedMempoolEntry> persistedEntries(List<MempoolEntry> entries) {
        return entries.stream()
                .map(entry -> new PersistedMempoolEntry(entry.transaction(), entry.arrivalTime()))
                .toList();
    }

    private void expirePersistent() {
        var before = mempool.entries();
        mempool.expire();
        if (persistMempool) mempoolStore.apply(persistedEntries(before), persistedMempoolEntries());
    }

    /**
     * Reloads locally persisted transactions through normal mempool admission.
     * Persistence is not a trust boundary: every transaction is revalidated
     * against the current chain and policy before becoming live again.
     */
    private void restorePersistentMempool() {
        List<PersistedMempoolEntry> stored = mempoolStore.load();
        if (stored.isEmpty()) return;

        Map<Hash256, PersistedMempoolEntry> pending = new LinkedHashMap<>();
        for (PersistedMempoolEntry entry : stored) pending.put(entry.transaction().txId(), entry);

        // Restore parents before children without relying on RocksDB key order.
        boolean progressed;
        do {
            progressed = false;
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                PersistedMempoolEntry persisted = iterator.next().getValue();
                Transaction tx = persisted.transaction();
                boolean parentStillPending = tx.inputs().stream()
                        .anyMatch(input -> pending.containsKey(input.previousOutput().transactionId()));
                if (parentStillPending) continue;
                try {
                    // Legacy transaction-only entries use arrivalTime=0 and are intentionally
                    // admitted with current time once, then rewritten in the metadata format.
                    if (persisted.arrivalTime() == 0L) {
                        mempool.admit(tx, context(), coins);
                    } else {
                        mempool.admitRestored(tx, persisted.arrivalTime(), context(), coins);
                    }
                } catch (MempoolAdmissionException | IllegalArgumentException ignored) {
                    // Stale, expired-by-policy, conflicting, or otherwise invalid after restart.
                }
                iterator.remove();
                progressed = true;
            }
        } while (progressed && !pending.isEmpty());
        // Cyclic/impossible dependency sets remain pending and are intentionally discarded.
    }

    private Block requireBlock(BlockIndex index) {
        return blocks.find(index.hash()).orElseThrow(() -> new IllegalStateException("Missing chain-update block: " + index.hash()));
    }
}
