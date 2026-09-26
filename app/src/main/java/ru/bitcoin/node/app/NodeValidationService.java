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
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.mempool.RocksDbMempoolStore;
import ru.bitcoin.node.storage.mempool.PersistedMempoolEntry;
import ru.bitcoin.node.storage.txindex.RocksDbTxIndexStore;
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
    private final RocksDbBlockIndexStore indexes;
    private final RocksDbBlockAvailabilityStore availability;
    private final RocksDbBlockValidationStatusStore validationStatus;
    private final BlockFailureManager failureManager;
    private final ChainReorganizationExecutor reorganizationExecutor;
    private final Mempool mempool;
    private final RocksDbMempoolStore mempoolStore;
    private final boolean persistMempool;
    private final boolean txIndexEnabled;
    private final RocksDbTxIndexStore txIndexStore;
    private final NetworkParameters parameters;
    private final AdjustedTime time;
    private final RocksDbUtxoStore utxos;
    private final BlockPruner blockPruner;
    private final RocksDbPruneStateStore pruneState;
    private final long pruneTargetBytes;
    private BlockIndex poolTip;
    private long revision;
    private boolean mempoolPersistenceDirty;
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
            return new PruneInfo(
                    blockPruner.enabled(),
                    blockPruner.automatic(),
                    hasPruned,
                    pruneHeight,
                    blockPruner.targetBytes()
            );
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

    public record PruneInfo(
            boolean enabled,
            boolean automatic,
            boolean hasPruned,
            long pruneHeight,
            long targetBytes
    ) {}

    /** Manual pruning entry point used by pruneblockchain RPC. */
    public long pruneToHeight(long requestedHeight) {
        synchronized (chain) {
            BlockPruner.Result result = blockPruner.pruneToHeight(chain.activeTip(), requestedHeight);
            if (result.highestPrunedHeight() >= 0) return result.highestPrunedHeight();
            return pruneState.highestPrunedHeight().orElse(0L);
        }
    }

    /**
     * Immutable active-chain index view used by RPC/read-only services.
     */
    public record ActiveBlockInfo(
            BlockIndex index,
            Hash256 nextBlockHash,
            long confirmations
    ) {}

    public Optional<ActiveBlockInfo> activeBlockInfo(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            BlockIndex candidate = lookup.find(hash);
            if (candidate == null) return Optional.empty();
            BlockIndex tip = chain.activeTip();
            if (candidate.height() > tip.height()) return Optional.empty();
            BlockIndex active = activeAncestors.at(tip, candidate.height(), lookup);
            if (active == null || !active.hash().equals(hash)) return Optional.empty();
            Hash256 next = candidate.height() < tip.height()
                    ? activeAncestors.at(tip, candidate.height() + 1L, lookup).hash()
                    : null;
            return Optional.of(new ActiveBlockInfo(
                    candidate,
                    next,
                    tip.height() - candidate.height() + 1L
            ));
        }
    }

    public Optional<ActiveBlockInfo> activeBlockInfo(long height) {
        synchronized (chain) {
            BlockIndex tip = chain.activeTip();
            if (height < 0 || height > tip.height()) return Optional.empty();
            BlockIndex index = activeAncestors.at(tip, height, lookup);
            if (index == null) return Optional.empty();
            Hash256 next = height < tip.height()
                    ? activeAncestors.at(tip, height + 1L, lookup).hash()
                    : null;
            return Optional.of(new ActiveBlockInfo(
                    index,
                    next,
                    tip.height() - height + 1L
            ));
        }
    }

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
        this(database, parameters, time, mempool, 0L, parameters.defaultAssumeValid(), true, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes) {
        this(database, parameters, time, mempool, pruneTargetBytes, parameters.defaultAssumeValid(), true, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes, Hash256 assumedValidBlock) {
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, true, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool) {
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, persistMempool, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool, boolean txIndexEnabled) {
        this.mempool = Objects.requireNonNull(mempool);
        this.mempoolStore = new RocksDbMempoolStore(database);
        this.persistMempool = persistMempool;
        this.txIndexEnabled = txIndexEnabled;
        this.txIndexStore = new RocksDbTxIndexStore(database);
        this.blockPruner = new BlockPruner(database, pruneTargetBytes);
        this.pruneState = new RocksDbPruneStateStore(database);
        this.pruneTargetBytes = pruneTargetBytes;
        this.parameters = Objects.requireNonNull(parameters);
        this.time = Objects.requireNonNull(time);
        utxos = new RocksDbUtxoStore(database);
        indexes = new RocksDbBlockIndexStore(database);
        availability = new RocksDbBlockAvailabilityStore(database);
        validationStatus = new RocksDbBlockValidationStatusStore(database);
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

        failureManager =
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
        reorganizationExecutor = new ChainReorganizationExecutor(
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
                        reorganizationExecutor,
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
            if (persistMempool) {
                mempoolStore.replace(persistedMempoolEntries());
                mempoolPersistenceDirty = false;
            }
            blockPruner.prune(chain.activeTip());
            if (txIndexEnabled) synchronizeTxIndex();
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
                if (txIndexEnabled) synchronizeTxIndex();
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
                markMempoolPersistenceDirty(before);
            }
        }
    }

    /** Current spendable output, optionally with the mempool overlaid on chainstate. */
    public Optional<TxOutInfo> txOut(Hash256 txid, long outputIndex, boolean includeMempool) {
        Objects.requireNonNull(txid, "txid");
        if (outputIndex < 0 || outputIndex > UInt32.MAX_VALUE)
            throw new IllegalArgumentException("vout out of range");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            OutPoint outPoint = new OutPoint(txid, new UInt32(outputIndex));
            if (includeMempool) {
                boolean spent = mempool.entries().stream().flatMap(entry -> entry.transaction().inputs().stream())
                        .anyMatch(input -> input.previousOutput().equals(outPoint));
                if (spent) return Optional.empty();
                for (MempoolEntry entry : mempool.entries()) {
                    Transaction tx = entry.transaction();
                    if (tx.txId().equals(txid)) {
                        if (outputIndex >= tx.outputs().size()) return Optional.empty();
                        TxOut output = tx.outputs().get((int) outputIndex);
                        return Optional.of(new TxOutInfo(output.value(), output.scriptPubKey(), 0L, false, 0L));
                    }
                }
            }
            return utxos.find(outPoint).map(coin -> new TxOutInfo(
                    coin.amount(), coin.scriptPubKey(), coin.height(), coin.coinbase(),
                    Math.addExact(Math.subtractExact(chain.activeTip().height(), coin.height()), 1L)));
        }
    }

    /** Stable UTXO-set statistics at the current active tip. */
    public UtxoSetInfo utxoSetInfo() {
        synchronized (chain) {
            var stats = utxos.statistics();
            return new UtxoSetInfo(chain.activeTip().height(), chain.activeTip().hash(),
                    stats.txouts(), stats.bogoSize(), stats.diskSize(), stats.totalAmount());
        }
    }

    public record TxOutInfo(long amount, byte[] scriptPubKey, long height, boolean coinbase, long confirmations) {
        public TxOutInfo { scriptPubKey = scriptPubKey.clone(); }
        @Override public byte[] scriptPubKey() { return scriptPubKey.clone(); }
    }
    public record UtxoSetInfo(long height, Hash256 bestBlock, long txouts, long bogoSize, long diskSize, long totalAmount) {}

    public Optional<MempoolEntry> mempoolEntry(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.entries().stream()
                    .filter(entry -> entry.transaction().txId().equals(txid))
                    .findFirst();
        }
    }

    /**
     * Finds a transaction only inside the explicitly supplied active-chain block.
     * This deliberately does not emulate a global txindex.
     */
    public Optional<Transaction> transactionInActiveBlock(
            Hash256 txid,
            Hash256 blockHash
    ) {
        Objects.requireNonNull(txid, "txid");
        Objects.requireNonNull(blockHash, "blockHash");
        synchronized (chain) {
            BlockIndex candidate = lookup.find(blockHash);
            if (candidate == null) return Optional.empty();
            BlockIndex tip = chain.activeTip();
            if (candidate.height() > tip.height()) return Optional.empty();
            BlockIndex active = activeAncestors.at(tip, candidate.height(), lookup);
            if (active == null || !active.hash().equals(blockHash)) return Optional.empty();
            return blocks.find(blockHash)
                    .flatMap(block -> block.transactions().stream()
                            .filter(tx -> tx.txId().equals(txid))
                            .findFirst());
        }
    }


    public boolean txIndexEnabled() {
        return txIndexEnabled;
    }

    /** Finds a confirmed transaction through the optional persistent transaction index. */
    public Optional<IndexedTransaction> indexedTransaction(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            if (!txIndexEnabled) return Optional.empty();
            synchronizeTxIndex();
            Hash256 blockHash = txIndexStore.findBlockHash(txid).orElse(null);
            if (blockHash == null) return Optional.empty();
            BlockIndex index = lookup.find(blockHash);
            if (index == null || index.height() > chain.activeTip().height()) return Optional.empty();
            BlockIndex active = activeAncestors.at(chain.activeTip(), index.height(), lookup);
            if (active == null || !active.hash().equals(blockHash)) return Optional.empty();
            return blocks.find(blockHash).flatMap(block -> block.transactions().stream()
                    .filter(tx -> tx.txId().equals(txid)).findFirst()
                    .map(tx -> new IndexedTransaction(tx, activeBlockInfo(blockHash).orElseThrow())));
        }
    }

    public record IndexedTransaction(Transaction transaction, ActiveBlockInfo blockInfo) {}

    private void synchronizeTxIndex() {
        if (!txIndexEnabled) return;
        BlockIndex activeTip = chain.activeTip();
        Hash256 indexedHash = txIndexStore.bestIndexedBlockHash().orElse(null);
        if (indexedHash == null) {
            BlockIndex genesis = activeAncestors.at(activeTip, 0L, lookup);
            if (genesis == null) throw new IllegalStateException("Cannot initialize txindex without genesis");
            txIndexStore.initializeAt(genesis.hash());
            indexedHash = genesis.hash();
        }
        BlockIndex indexed = lookup.find(indexedHash);
        if (indexed == null) throw new IllegalStateException("txindex cursor references unknown block: " + indexedHash.toDisplayHex());
        ReorganizationPlan plan = ReorganizationPlanner.plan(indexed, activeTip, lookup);
        for (BlockIndex disconnect : plan.blocksToDisconnect()) {
            Block block = blocks.find(disconnect.hash()).orElseThrow(() ->
                    new IllegalStateException("Block body required to rewind txindex: " + disconnect.hash().toDisplayHex()));
            txIndexStore.rewind(block, disconnect.previousBlockHash());
        }
        for (BlockIndex connect : plan.blocksToConnect()) {
            Block block = blocks.find(connect.hash()).orElseThrow(() ->
                    new IllegalStateException("Block body required to synchronize txindex: " + connect.hash().toDisplayHex()));
            txIndexStore.append(block);
        }
        Hash256 synchronizedHash = txIndexStore.bestIndexedBlockHash().orElseThrow();
        if (!synchronizedHash.equals(activeTip.hash())) {
            throw new IllegalStateException("txindex synchronization stopped at "
                    + synchronizedHash.toDisplayHex() + " instead of active tip " + activeTip.hash().toDisplayHex());
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
                markMempoolPersistenceDirty(before);
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

    /** Manually invalidate a known non-genesis block and activate the best remaining chain. */
    public void invalidateBlock(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            synchronizePool();
            BlockIndex index = lookup.find(hash);
            if (index == null) throw new IllegalArgumentException("Block not found");
            if (index.height() == 0) throw new IllegalArgumentException("Genesis block cannot be invalidated");
            failureManager.markFailed(hash);
            activateBestEligibleChain();
            finishManualChainChange();
        }
    }

    /** Clear failure state related to a known block and reconsider the strongest eligible chain. */
    public void reconsiderBlock(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            synchronizePool();
            if (lookup.find(hash) == null) throw new IllegalArgumentException("Block not found");
            failureManager.reconsider(hash);
            activateBestEligibleChain();
            finishManualChainChange();
        }
    }

    public boolean isBlockFailed(Hash256 hash) {
        synchronized (chain) {
            return failureManager.isFailed(hash);
        }
    }

    private void activateBestEligibleChain() {
        BlockIndex candidate = indexes.findBest(stored ->
                        blocks.find(stored.hash()).isPresent() && !failureManager.isFailed(stored.hash()))
                .map(BlockIndexStorageMapper::fromStored)
                .orElseThrow(() -> new IllegalStateException("No eligible block with available data remains"));

        BlockIndex current = chain.activeTip();
        if (current.hash().equals(candidate.hash())) return;

        /*
         * Manual chain control is deliberately different from ordinary best-chain
         * selection. After invalidateblock the current tip can have more chainwork
         * than every remaining eligible branch, but it is no longer a valid
         * candidate. ChainState.prepareUpdate() correctly refuses a lower-work
         * chain during normal block processing, so using it here would leave the
         * manually-invalidated branch active. Build the forced transition directly
         * and let the normal reorganization executor perform the same validated,
         * atomic UTXO/undo transition used by automatic reorgs.
         */
        ReorganizationPlan plan = ReorganizationPlanner.plan(current, candidate, lookup);
        reorganizationExecutor.execute(new ChainUpdate(current, candidate, plan));
    }

    private void finishManualChainChange() {
        synchronizePool();
        if (txIndexEnabled) synchronizeTxIndex();
        initialBlockDownload.update(chain.activeTip());
        revision++;
        chain.notifyAll();
    }

    public BlockIndex activeTip() {
        synchronized (chain) {
            return chain.activeTip();
        }
    }

    /** Persistent branch tips for Core-style getchaintips diagnostics. */
    public List<ChainTipInfo> chainTips() {
        synchronized (chain) {
            List<StoredBlockIndex> all = indexes.findAll();
            Set<Hash256> parents = new HashSet<>();
            for (StoredBlockIndex index : all) {
                if (index.height() > 0) parents.add(index.previousBlockHash());
            }
            BlockIndex activeTip = chain.activeTip();
            List<ChainTipInfo> result = new ArrayList<>();
            for (StoredBlockIndex stored : all) {
                if (parents.contains(stored.hash())) continue;
                BlockIndex tip = BlockIndexStorageMapper.fromStored(stored);
                long branchLength = branchLengthFromActive(tip, activeTip);
                String status;
                if (tip.hash().equals(activeTip.hash())) {
                    status = "active";
                } else if (failureManager.isFailed(tip.hash())) {
                    status = "invalid";
                } else if (!availability.hasData(tip.hash())) {
                    status = "headers-only";
                } else if (validationStatus.isScriptsValid(tip.hash())) {
                    status = "valid-fork";
                } else {
                    status = "valid-headers";
                }
                result.add(new ChainTipInfo(tip.height(), tip.hash(), branchLength, status));
            }
            result.sort(Comparator.comparingLong(ChainTipInfo::height).reversed()
                    .thenComparing(info -> info.hash().toDisplayHex()));
            return List.copyOf(result);
        }
    }

    private long branchLengthFromActive(BlockIndex tip, BlockIndex activeTip) {
        BlockIndex a = tip;
        BlockIndex b = activeTip;
        while (a.height() > b.height()) a = requireParent(a);
        while (b.height() > a.height()) b = requireParent(b);
        while (!a.hash().equals(b.hash())) {
            a = requireParent(a);
            b = requireParent(b);
        }
        return tip.height() - a.height();
    }

    private BlockIndex requireParent(BlockIndex index) {
        BlockIndex parent = lookup.find(index.previousBlockHash());
        if (parent == null) throw new IllegalStateException(
                "Missing BlockIndex ancestor for chain tip " + index.hash().toDisplayHex());
        return parent;
    }

    public record ChainTipInfo(long height, Hash256 hash, long branchLength, String status) {}

    /**
     * BIP23 proposal validation against the current active tip. The block is never
     * persisted, relayed, or connected and proof of work is deliberately skipped.
     */
    public ProposalResult validateBlockProposal(Block block) {
        Objects.requireNonNull(block, "block");
        synchronized (chain) {
            BlockIndex known = lookup.find(block.hash());
            if (known != null) return new ProposalResult(false, "duplicate");

            BlockIndex parent = chain.activeTip();
            if (!block.header().previousBlockHash().equals(parent.hash()))
                return new ProposalResult(false, "inconclusive-not-best-prevblk");

            try {
                ru.bitcoin.node.consensus.block.BlockValidator.validateStructure(block);
                ChainHeaderValidator.validateWithoutProofOfWork(
                        block.header(), parent, lookup, parameters, time);
                long height = Math.addExact(parent.height(), 1L);
                long previousMtp = MedianTimePast.calculate(parent, lookup);
                long lockTimeCutoff = LockTimeCutoff.calculate(
                        height, block.header().timestamp().value(), previousMtp, parameters);
                BlockIndex candidate = BlockIndexFactory.createChild(parent, block.header());
                ru.bitcoin.node.chain.utxo.BlockConnectChangesBuilder.build(
                        block, height, lockTimeCutoff, previousMtp, utxos, parameters,
                        new AncestorMedianTimePastResolver(candidate, lookup));
                return new ProposalResult(true, null);
            } catch (ru.bitcoin.node.consensus.block.BlockValidationException
                     | ru.bitcoin.node.consensus.block.BlockHeaderValidationException
                     | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException
                     | ru.bitcoin.node.script.ScriptParseException
                     | IllegalArgumentException exception) {
                String reason = exception.getMessage();
                return new ProposalResult(false, reason == null || reason.isBlank() ? "rejected" : reason);
            }
        }
    }

    public record ProposalResult(boolean valid, String rejectReason) { }

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
        markMempoolPersistenceDirty(before);
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
        markMempoolPersistenceDirty(before);
    }

    /**
     * Writes one coherent durable mempool snapshot. Normal mempool mutation does
     * not perform RocksDB I/O; persistence is flushed explicitly during clean
     * node shutdown (and may be invoked by operational tooling).
     */
    public void flushPersistentMempool() {
        if (!persistMempool) return;
        synchronized (chain) {
            synchronizePool();
            var beforeExpiry = mempool.entries();
            mempool.expire();
            markMempoolPersistenceDirty(beforeExpiry);
            if (!mempoolPersistenceDirty) return;
            mempoolStore.replace(persistedMempoolEntries());
            mempoolPersistenceDirty = false;
        }
    }

    public boolean isMempoolPersistenceDirty() {
        synchronized (chain) {
            return persistMempool && mempoolPersistenceDirty;
        }
    }

    private void markMempoolPersistenceDirty(List<MempoolEntry> before) {
        if (!persistMempool) return;
        if (!before.equals(mempool.entries())) {
            mempoolPersistenceDirty = true;
        }
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
