package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.*;
import ru.bitcoin.node.chain.utxo.BlockConnectChangesBuilder;
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
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.mempool.RocksDbMempoolStore;
import ru.bitcoin.node.storage.mempool.PersistedMempoolEntry;
import ru.bitcoin.node.storage.txindex.RocksDbTxIndexStore;
import ru.bitcoin.node.storage.txospender.RocksDbTxOutSpenderIndexStore;
import ru.bitcoin.node.storage.coinstats.RocksDbCoinStatsIndexStore;
import ru.bitcoin.node.storage.blockfilter.RocksDbBlockFilterIndexStore;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.util.*;

/**
 * Application entry point for block and transaction admission. The caller owns
 * the database lifetime and must not mutate these stores through another processor.
 */
public final class NodeValidationService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(NodeValidationService.class.getName());
    private static final long IBD_TELEMETRY_INTERVAL_NANOS = java.time.Duration.ofSeconds(5).toNanos();
    private long ibdTelemetryLastLogNanos;
    private long ibdTelemetryBlocks;
    private long ibdTelemetryTransactions;
    private long ibdTelemetryProcessorNanos;
    private long ibdTelemetryPoolNanos;
    private long ibdTelemetryMaintenanceNanos;
    private RocksDbDatabase.IoStats ibdTelemetryDbBaseline;
    private RocksDbDatabase.NamespaceIoStats ibdTelemetryNamespaceBaseline;
    private RocksDbUtxoStore.CacheStats ibdTelemetryCacheBaseline;
    private StoredBlockIndexLookup.DiagnosticSnapshot ibdTelemetryBlockIndexBaseline;
    private BlockProcessor.DiagnosticSnapshot ibdTelemetryProcessorBaseline;
    private Bip34AncestryCache.DiagnosticSnapshot ibdTelemetryBip34Baseline;
    private BlockConnectChangesBuilder.DiagnosticSnapshot ibdTelemetryConnectBaseline;
    private final ChainState chain;
    private final StoredBlockIndexLookup lookup;

    /** Shared immutable header/index metadata; mutable validation status is stored separately. */
    public StoredBlockIndexLookup storedBlockIndexLookup() { return lookup; }
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
    private final ru.bitcoin.node.storage.mempool.RocksDbMempoolFeeDeltaStore mempoolFeeDeltaStore;
    private final boolean persistMempool;
    private final boolean txIndexEnabled;
    private final RocksDbTxIndexStore txIndexStore;
    private final boolean txOutSpenderIndexEnabled;
    private final RocksDbTxOutSpenderIndexStore txOutSpenderIndexStore;
    private final boolean coinStatsIndexEnabled;
    private final RocksDbCoinStatsIndexStore coinStatsIndexStore;
    private final CoinStatsIndexService coinStatsIndex;
    private final boolean blockFilterIndexEnabled;
    private final RocksDbBlockFilterIndexStore blockFilterIndexStore;
    private final BlockFilterIndexService blockFilterIndex;
    private final RocksDbUndoStore undos;
    private final NetworkParameters parameters;
    private final AdjustedTime time;
    private final RocksDbUtxoStore utxos;
    private final ru.bitcoin.node.storage.utxo.RocksDbSnapshotChainStateStore snapshotChainStateStore;
    private final AssumeUtxoBackgroundValidator assumeUtxoBackgroundValidator;
    private volatile boolean backgroundValidationStop;
    private volatile Thread backgroundValidationThread;
    private volatile ru.bitcoin.node.p2p.sync.BlockDownloadScheduler backgroundBlockDownloadScheduler;
    private final RocksDbDatabase database;
    private final BlockPruner blockPruner;
    private final RocksDbPruneStateStore pruneState;
    private final long pruneTargetBytes;
    private BlockIndex poolTip;
    private long revision;
    private boolean mempoolPersistenceDirty;
    private final ActiveChainAncestors activeAncestors = new ActiveChainAncestors();
    private final InitialBlockDownloadState initialBlockDownload;
    private final InitialSyncUtxoPrefetcher initialSyncPrefetcher =
            new InitialSyncUtxoPrefetcher(inputs -> utxoWarmInputs(inputs));
    private volatile boolean initialSyncPrefetchEnabled = true;

    private void utxoWarmInputs(List<OutPoint> inputs) { utxos.warmPersistentInputs(inputs); }

    public void initialSyncPrefetchEnabled(boolean enabled) { initialSyncPrefetchEnabled = enabled; }

    public void prefetchInitialSyncInputsAhead(List<Block> blocks) {
        if (initialSyncPrefetchEnabled && !blocks.isEmpty()) {
            initialSyncPrefetcher.offer(List.copyOf(initialSyncExternalInputs(blocks, 8192)));
        }
    }

    /** Immutable network parameters used by this validation instance. */
    public NetworkParameters networkParameters() {
        return parameters;
    }

    public long revision() {
        synchronized (chain) {
            synchronizePool();
            return revision;
        }
    }

    public record MiningSnapshot(Block block, List<MempoolEntry> entries, long height, long medianTimePast,
                                 long minimumTimestamp, long revision,
                                 List<ru.bitcoin.node.mining.MiningVersionBits.DeploymentView> deployments) {
    }

    public MiningSnapshot miningSnapshot(byte[] payout, byte[] extraNonce, long weight, FeeRate feeRate) {
        return miningSnapshot(payout, extraNonce, weight, feeRate, null);
    }

    /** GBT snapshot whose preferred version respects the client's declared version-bits rules. */
    public MiningSnapshot miningSnapshot(
            byte[] payout, byte[] extraNonce, long weight, FeeRate feeRate, Set<String> supportedRules) {
        synchronized (chain) {
            Block block = createMiningTemplate(payout, extraNonce, weight, feeRate, supportedRules);
            BlockIndex parent = chain.activeTip();
            return new MiningSnapshot(block, mempool.entries(), parent.height() + 1,
                    MedianTimePast.calculate(parent, lookup),
                    ChainHeaderValidator.minimumTimestamp(parent, MedianTimePast.calculate(parent, lookup), parameters),
                    revision, miningDeployments(parent));
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

    /**
     * Highest historical height that may currently be pruned. While an AssumeUTXO
     * background chainstate is still being validated, blocks above its durable tip
     * must remain available for validation. A full trailing prune/reorg window is
     * also retained behind the background tip for undo/index consumers. Once validation
     * is complete the normal active-chain reorg-window rule is sufficient again.
     */
    private long assumeUtxoPruneCeiling() {
        if (snapshotChainStateStore.load().isEmpty()) return Long.MAX_VALUE;
        var background = assumeUtxoBackgroundValidator.state();
        if (background == null) return 0L;
        if (background.status() == ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED)
            return Long.MAX_VALUE;
        return Math.max(0L, background.tipHeight() - BlockPruner.MIN_BLOCKS_TO_KEEP);
    }

    /** Manual pruning entry point used by pruneblockchain RPC. */
    public long pruneToHeight(long requestedHeight) {
        synchronized (chain) {
            BlockPruner.Result result = blockPruner.pruneToHeight(chain.activeTip(), requestedHeight, assumeUtxoPruneCeiling());
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
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, persistMempool, txIndexEnabled, false, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool, boolean txIndexEnabled,
                                 boolean coinStatsIndexEnabled) {
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, persistMempool,
                txIndexEnabled, coinStatsIndexEnabled, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool, boolean txIndexEnabled,
                                 boolean coinStatsIndexEnabled, boolean blockFilterIndexEnabled) {
        this(database, parameters, time, mempool, pruneTargetBytes, assumedValidBlock, persistMempool,
                txIndexEnabled, coinStatsIndexEnabled, blockFilterIndexEnabled, false);
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool, long pruneTargetBytes,
                                 Hash256 assumedValidBlock, boolean persistMempool, boolean txIndexEnabled,
                                 boolean coinStatsIndexEnabled, boolean blockFilterIndexEnabled,
                                 boolean txOutSpenderIndexEnabled) {
        this.database = Objects.requireNonNull(database);
        this.mempool = Objects.requireNonNull(mempool);
        this.mempoolStore = new RocksDbMempoolStore(database);
        this.mempoolFeeDeltaStore = new ru.bitcoin.node.storage.mempool.RocksDbMempoolFeeDeltaStore(database);
        this.persistMempool = persistMempool;
        this.txIndexEnabled = txIndexEnabled;
        this.txIndexStore = new RocksDbTxIndexStore(database);
        this.txOutSpenderIndexEnabled = txOutSpenderIndexEnabled;
        this.txOutSpenderIndexStore = new RocksDbTxOutSpenderIndexStore(database);
        this.coinStatsIndexEnabled = coinStatsIndexEnabled;
        this.coinStatsIndexStore = new RocksDbCoinStatsIndexStore(database);
        this.blockFilterIndexEnabled = blockFilterIndexEnabled;
        this.blockFilterIndexStore = new RocksDbBlockFilterIndexStore(database);
        this.blockPruner = new BlockPruner(database, pruneTargetBytes, parameters.pruneAfterHeight());
        this.pruneState = new RocksDbPruneStateStore(database);
        this.pruneTargetBytes = pruneTargetBytes;
        this.parameters = Objects.requireNonNull(parameters);
        this.time = Objects.requireNonNull(time);
        new ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoFinalizer(database).finalizeOnStartup();
        snapshotChainStateStore = new ru.bitcoin.node.storage.utxo.RocksDbSnapshotChainStateStore(database);
        boolean snapshotActiveAtStartup = snapshotChainStateStore.load().isPresent();
        byte startupUtxoPrefix = snapshotActiveAtStartup
                ? ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING
                : ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.UTXO;
        // The primary chainstate gets a bounded positive UTXO read cache. 500k entries
        // is intentionally conservative; snapshot/background stores keep the uncached default.
        utxos = new RocksDbUtxoStore(database, startupUtxoPrefix, 500_000);
        indexes = new RocksDbBlockIndexStore(database);
        availability = new RocksDbBlockAvailabilityStore(database);
        validationStatus = new RocksDbBlockValidationStatusStore(database);
        var failures =
                new RocksDbBlockFailureStore(
                        database
                );
        var tips = new RocksDbChainStateStore(database);
        undos = new RocksDbUndoStore(database);
        blocks = new RocksDbBlockStore(database);
        lookup = new StoredBlockIndexLookup(indexes);
        assumeUtxoBackgroundValidator = new AssumeUtxoBackgroundValidator(database, parameters, lookup);
        assumeUtxoBackgroundValidator.initializeIfNeeded();
        coinStatsIndex = new CoinStatsIndexService(coinStatsIndexStore, blocks, undos, lookup, activeAncestors);
        blockFilterIndex = new BlockFilterIndexService(blockFilterIndexStore, blocks, undos, lookup, activeAncestors);
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
        // The persisted selected chain already excludes known failed ancestry.
        // Seed the validation resolver as well as the download resolver so its
        // first historical child does not walk all parents back to genesis.
        failureResolver.seedKnownValid(chain.activeTip());
        initialBlockDownload = new InitialBlockDownloadState(parameters, time::currentTimeSeconds);
        initialBlockDownload.update(chain.activeTip());
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
            if (persistMempool) {
                mempoolFeeDeltaStore.load().forEach(mempool::restoreFeeDelta);
                restorePersistentMempool();
            }
            mempool.revalidate(context(), coins, Set.of());
            mempool.expire();
            if (persistMempool) {
                mempoolStore.replace(persistedMempoolEntries());
                mempoolPersistenceDirty = false;
            }
            if (txIndexEnabled) synchronizeTxIndex();
            if (txOutSpenderIndexEnabled) synchronizeTxOutSpenderIndex();
            if (coinStatsIndexEnabled) synchronizeCoinStatsIndex();
            if (blockFilterIndexEnabled) synchronizeBlockFilterIndex();
            if (blockPruner.automatic()) blockPruner.prune(chain.activeTip(), assumeUtxoPruneCeiling());
            initialBlockDownload.update(chain.activeTip());
        }
        ensureBackgroundValidationWorker();
    }

    public BlockProcessingResult processBlock(Block block) {
        synchronized (chain) {
            synchronizePool();
            BlockProcessingResult result = processor.process(block);
            // Chain storage has committed. On a history/storage failure poolTip stays old,
            // and every later API call retries synchronization before exposing the pool.
            synchronizePool();
            if (result == BlockProcessingResult.CONNECTED) {
                activeAncestors.rememberCommitted(chain.activeTip());
                if (txIndexEnabled) synchronizeTxIndex();
                if (txOutSpenderIndexEnabled) synchronizeTxOutSpenderIndex();
                if (coinStatsIndexEnabled) synchronizeCoinStatsIndex();
                if (blockFilterIndexEnabled) synchronizeBlockFilterIndex();
                if (blockPruner.automatic()) blockPruner.prune(chain.activeTip(), assumeUtxoPruneCeiling());
                initialBlockDownload.update(chain.activeTip());
            }
            return result;
        }
    }

    /**
     * IBD-oriented ordered block admission. Consensus validation and chain commits still
     * happen for every block in order, but expensive derived-index/pruning maintenance is
     * coalesced once for the whole contiguous batch. Mempool synchronization remains
     * per-connected-block so externally visible transaction admission semantics are unchanged.
     */
    public List<BlockProcessingResult> processInitialSyncBatch(List<Block> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) return List.of();

        synchronized (chain) {
            long batchStarted = System.nanoTime();
            RocksDbDatabase.IoStats dbBefore = database.ioStats();
            RocksDbUtxoStore.CacheStats cacheBefore = utxos.cacheStats();

            long poolStarted = System.nanoTime();
            synchronizePool();
            long poolNanos = System.nanoTime() - poolStarted;

            // Core-style CoinsTip semantics: connect blocks against a process-visible
            // write-back chainstate and let cache pressure/time decide durable checkpoints.
            // A crash reopens the last complete checkpoint; active in-memory ChainState is
            // never advertised as durable by the database itself.
            database.enableChainstateWriteBack();

            // The complete ordered batch is already in RAM. Resolve all old-chain inputs in
            // one MultiGet before ConnectBlock starts. Outputs created by an earlier block in
            // this same batch replace any cached absence at that block's successful commit.
            // This turns per-block random RocksDB reads into a first-touch batch prefetch.
            if (initialSyncPrefetchEnabled) prefetchInitialSyncInputs(batch);

            long processorStarted = System.nanoTime();
            List<BlockProcessingResult> results = new ArrayList<>(batch.size());
            for (Block block : batch) {
                Objects.requireNonNull(block, "batch block");
                BlockProcessingResult result = processor.process(block);
                results.add(result);
                if (result == BlockProcessingResult.CONNECTED) {
                    activeAncestors.rememberCommitted(chain.activeTip());
                }
            }
            database.flushChainstateIfNeeded();
            long processorNanos = System.nanoTime() - processorStarted;

            boolean connectedAny = results.stream()
                    .anyMatch(result -> result == BlockProcessingResult.CONNECTED);

            long maintenanceStarted = System.nanoTime();
            synchronizePool();
            if (connectedAny) {
                if (txIndexEnabled) synchronizeTxIndex();
                if (txOutSpenderIndexEnabled) synchronizeTxOutSpenderIndex();
                if (coinStatsIndexEnabled) synchronizeCoinStatsIndex();
                if (blockFilterIndexEnabled) synchronizeBlockFilterIndex();
                if (blockPruner.automatic()) blockPruner.prune(chain.activeTip(), assumeUtxoPruneCeiling());
                initialBlockDownload.update(chain.activeTip());
                if (!initialBlockDownload.isInitialBlockDownload()) {
                    database.forceFlushChainstate();
                    database.disableChainstateWriteBack(false);
                }
            }
            long maintenanceNanos = System.nanoTime() - maintenanceStarted;
            poolNanos += maintenanceNanos; // includes the final pool reconciliation and maintenance boundary

            recordInitialSyncTelemetry(batch, batchStarted, processorNanos, poolNanos,
                    maintenanceNanos, dbBefore, cacheBefore);
            return List.copyOf(results);
        }
    }

    private void prefetchInitialSyncInputs(List<Block> batch) {
        var inputs = initialSyncExternalInputs(batch, 8192);
        if (!inputs.isEmpty()) utxos.findAll(inputs);
    }

    /** Serialize header persistence with block activation and failed-branch updates. */
    public List<BlockIndex> processHeaders(HeaderSyncService service,
            ru.bitcoin.node.p2p.message.HeadersMessage message) {
        java.util.Objects.requireNonNull(service, "service");
        java.util.Objects.requireNonNull(message, "message");
        synchronized (chain) {
            return service.process(message);
        }
    }

    // Prefetch is only a read hint, never an alternative source of coins. Inputs
    // produced earlier in this ordered run will be resolved by ConnectBlock's
    // committed UTXO updates instead of querying their absence in the old DB.
    static Set<OutPoint> initialSyncExternalInputs(List<Block> batch) {
        return initialSyncExternalInputs(batch, Integer.MAX_VALUE);
    }

    private static Set<OutPoint> initialSyncExternalInputs(List<Block> batch, int limit) {
        LinkedHashSet<OutPoint> inputs = new LinkedHashSet<>();
        Map<Hash256, Integer> earlierOutputs = new HashMap<>();
        for (Block block : batch) {
            Objects.requireNonNull(block, "batch block");
            for (Transaction transaction : block.transactions()) {
                if (!transaction.isCoinbase()) {
                    for (var input : transaction.inputs()) {
                        OutPoint outPoint = input.previousOutput();
                        Integer outputs = earlierOutputs.get(outPoint.transactionId());
                        if (outputs == null || outPoint.outputIndex().value() >= outputs) {
                            inputs.add(outPoint);
                            if (inputs.size() >= limit) return inputs;
                        }
                    }
                }
                earlierOutputs.put(transaction.txId(), transaction.outputs().size());
            }
        }
        return inputs;
    }

    private void recordInitialSyncTelemetry(List<Block> batch, long batchStarted, long processorNanos,
                                            long poolNanos, long maintenanceNanos,
                                            RocksDbDatabase.IoStats dbBefore,
                                            RocksDbUtxoStore.CacheStats cacheBefore) {
        long now = System.nanoTime();
        long transactions = 0;
        for (Block block : batch) transactions += block.transactions().size();

        if (ibdTelemetryDbBaseline == null) {
            ibdTelemetryDbBaseline = dbBefore;
            ibdTelemetryNamespaceBaseline = database.namespaceIoStats();
            ibdTelemetryCacheBaseline = cacheBefore;
            ibdTelemetryBlockIndexBaseline = lookup.diagnosticSnapshot();
            ibdTelemetryProcessorBaseline = processor.diagnosticSnapshot();
            ibdTelemetryBip34Baseline = processor.bip34AncestryDiagnosticSnapshot();
            ibdTelemetryConnectBaseline = BlockConnectChangesBuilder.diagnosticSnapshot();
            ibdTelemetryLastLogNanos = batchStarted;
        }

        ibdTelemetryBlocks += batch.size();
        ibdTelemetryTransactions += transactions;
        ibdTelemetryProcessorNanos += processorNanos;
        ibdTelemetryPoolNanos += poolNanos;
        ibdTelemetryMaintenanceNanos += maintenanceNanos;

        long elapsed = now - ibdTelemetryLastLogNanos;
        if (elapsed < IBD_TELEMETRY_INTERVAL_NANOS) return;

        RocksDbDatabase.IoStats db = database.ioStats().minus(ibdTelemetryDbBaseline);
        RocksDbDatabase.NamespaceIoStats namespaceDb = database.namespaceIoStats().minus(ibdTelemetryNamespaceBaseline);
        RocksDbUtxoStore.CacheStats cacheNow = utxos.cacheStats();
        long hits = cacheNow.hits() - ibdTelemetryCacheBaseline.hits();
        long misses = cacheNow.misses() - ibdTelemetryCacheBaseline.misses();
        long cacheLookups = hits + misses;
        double seconds = elapsed / 1_000_000_000.0d;
        double blocksPerSecond = ibdTelemetryBlocks / seconds;
        double txPerSecond = ibdTelemetryTransactions / seconds;
        double hitRate = cacheLookups == 0 ? 0.0d : (100.0d * hits / cacheLookups);

        LOG.log(System.Logger.Level.INFO, String.format(java.util.Locale.ROOT,
                "IBD PERF: height=%,d blocks=%,d %.1f blk/s tx=%,d %.0f tx/s " +
                        "processor=%.1fms/block pool+maint=%.1fms/block maint=%.1fms/block " +
                        "rocks[get=%,d %.1fms, batches=%,d %.1fms, syncBatches=%,d, walSync=%,d %.1fms] " +
                        "utxoCache[hits=%,d misses=%,d hit=%.1f%% size=%,d/%,d]",
                chain.activeTip().height(), ibdTelemetryBlocks, blocksPerSecond,
                ibdTelemetryTransactions, txPerSecond,
                ibdTelemetryProcessorNanos / 1_000_000.0d / Math.max(1L, ibdTelemetryBlocks),
                ibdTelemetryPoolNanos / 1_000_000.0d / Math.max(1L, ibdTelemetryBlocks),
                ibdTelemetryMaintenanceNanos / 1_000_000.0d / Math.max(1L, ibdTelemetryBlocks),
                db.gets(), db.getNanos() / 1_000_000.0d,
                db.writeBatches(), db.writeBatchNanos() / 1_000_000.0d, db.syncWriteBatches(),
                db.walSyncs(), db.walSyncNanos() / 1_000_000.0d,
                hits, misses, hitRate, cacheNow.size(), cacheNow.capacity()));

        LOG.log(System.Logger.Level.INFO, "IBD ROCKS GETS BY NS: " + formatNamespaceGets(namespaceDb));
        var metadataReads = database.readCacheStats();
        LOG.log(System.Logger.Level.INFO, String.format(java.util.Locale.ROOT,
                "IBD METADATA CACHE: cumulativeNativeKeys=%,d cumulativeHits=%,d",
                metadataReads.nativeKeys(), metadataReads.metadataHits()));
        var warm = database.warmReadStats();
        if (warm.keys() > 0) LOG.log(System.Logger.Level.INFO, String.format(java.util.Locale.ROOT,
                "IBD UTXO PREFETCH: cumulativeKeys=%,d cumulativeReadMs=%.1f",
                warm.keys(), warm.nanos() / 1_000_000.0));
        var blockIndexDiagnostics = lookup.diagnosticSnapshot().minus(ibdTelemetryBlockIndexBaseline);
        LOG.log(System.Logger.Level.INFO, "IBD BLOCKINDEX MISS SAMPLES: misses="
                + String.format(java.util.Locale.ROOT, "%,d", blockIndexDiagnostics.persistentMisses())
                + " sampleRate=1/128 callers=" + formatBlockIndexCallers(blockIndexDiagnostics));
        var processorDiagnostics = processor.diagnosticSnapshot().minus(ibdTelemetryProcessorBaseline);
        LOG.log(System.Logger.Level.INFO, formatProcessorDiagnostics(processorDiagnostics));
        var bip34Diagnostics = processor.bip34AncestryDiagnosticSnapshot().minus(ibdTelemetryBip34Baseline);
        LOG.log(System.Logger.Level.INFO, String.format(java.util.Locale.ROOT,
                "IBD BIP34 ANCESTRY: fullProofs=%,d inherited=%,d",
                bip34Diagnostics.fullProofs(), bip34Diagnostics.inheritedProofs()));
        var connectDiagnostics = BlockConnectChangesBuilder.diagnosticSnapshot().minus(ibdTelemetryConnectBaseline);
        LOG.log(System.Logger.Level.INFO, formatConnectDiagnostics(connectDiagnostics));

        ibdTelemetryLastLogNanos = now;
        ibdTelemetryBlocks = 0;
        ibdTelemetryTransactions = 0;
        ibdTelemetryProcessorNanos = 0;
        ibdTelemetryPoolNanos = 0;
        ibdTelemetryMaintenanceNanos = 0;
        ibdTelemetryDbBaseline = database.ioStats();
        ibdTelemetryNamespaceBaseline = database.namespaceIoStats();
        ibdTelemetryCacheBaseline = cacheNow;
        ibdTelemetryBlockIndexBaseline = lookup.diagnosticSnapshot();
        ibdTelemetryProcessorBaseline = processor.diagnosticSnapshot();
        ibdTelemetryBip34Baseline = processor.bip34AncestryDiagnosticSnapshot();
        ibdTelemetryConnectBaseline = BlockConnectChangesBuilder.diagnosticSnapshot();
    }

    private static String formatConnectDiagnostics(BlockConnectChangesBuilder.DiagnosticSnapshot stats) {
        long blocks = Math.max(1L, stats.blocks());
        long totalNanos = stats.structureNanos() + stats.setupNanos() + stats.bip30Nanos()
                + stats.finalitySigOpsNanos() + stats.contextInputsNanos() + stats.sequenceLocksNanos()
                + stats.scriptsNanos() + stats.spendInputsNanos() + stats.createOutputsNanos()
                + stats.rewardNanos();
        return String.format(java.util.Locale.ROOT,
                "IBD CONNECT PHASES: blocks=%,d measured=%.1fms %.3fms/block "
                        + "structure=%.1f setup=%.1f bip30=%.1f finality+sigops=%.1f "
                        + "contextInputs=%.1f sequenceLocks=%.1f scripts=%.1f "
                        + "spendInputs=%.1f createOutputs=%.1f reward=%.1f",
                stats.blocks(), totalNanos / 1_000_000.0d,
                totalNanos / 1_000_000.0d / blocks,
                stats.structureNanos() / 1_000_000.0d,
                stats.setupNanos() / 1_000_000.0d,
                stats.bip30Nanos() / 1_000_000.0d,
                stats.finalitySigOpsNanos() / 1_000_000.0d,
                stats.contextInputsNanos() / 1_000_000.0d,
                stats.sequenceLocksNanos() / 1_000_000.0d,
                stats.scriptsNanos() / 1_000_000.0d,
                stats.spendInputsNanos() / 1_000_000.0d,
                stats.createOutputsNanos() / 1_000_000.0d,
                stats.rewardNanos() / 1_000_000.0d);
    }

    private static String formatProcessorDiagnostics(BlockProcessor.DiagnosticSnapshot stats) {
        long blocks = Math.max(1L, stats.processed());
        double measuredMs = (stats.structureKnownNanos() + stats.parentHeaderNanos()
                + stats.witnessSignetNanos() + stats.prepareUpdateNanos()
                + stats.reorgPrepareNanos() + stats.commitNanos()) / 1_000_000.0d;
        return String.format(java.util.Locale.ROOT,
                "IBD PROCESSOR PHASES: blocks=%,d measured=%.1fms %.3fms/block "
                        + "structure+known=%.1fms parent+header=%.1fms witness+signet=%.1fms "
                        + "prepareUpdate=%.1fms reorgPrepare=%.1fms commit=%.1fms",
                stats.processed(), measuredMs, measuredMs / blocks,
                stats.structureKnownNanos() / 1_000_000.0d,
                stats.parentHeaderNanos() / 1_000_000.0d,
                stats.witnessSignetNanos() / 1_000_000.0d,
                stats.prepareUpdateNanos() / 1_000_000.0d,
                stats.reorgPrepareNanos() / 1_000_000.0d,
                stats.commitNanos() / 1_000_000.0d);
    }

    private static String formatBlockIndexCallers(StoredBlockIndexLookup.DiagnosticSnapshot stats) {
        var entries = new ArrayList<>(stats.samplesByCaller().entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringJoiner result = new StringJoiner(", ", "[", "]");
        int limit = Math.min(12, entries.size());
        for (int i = 0; i < limit; i++) {
            var entry = entries.get(i);
            result.add(entry.getKey() + "=" + String.format(java.util.Locale.ROOT, "%,d", entry.getValue()));
        }
        return result.toString();
    }

    private static String formatNamespaceGets(RocksDbDatabase.NamespaceIoStats stats) {
        record Entry(int namespace, long gets, long nanos) {}
        List<Entry> entries = new ArrayList<>();
        long[] gets = stats.gets();
        long[] nanos = stats.getNanos();
        for (int i = 0; i < gets.length; i++) {
            if (gets[i] > 0) entries.add(new Entry(i, gets[i], nanos[i]));
        }
        entries.sort(Comparator.comparingLong(Entry::gets).reversed());
        StringJoiner result = new StringJoiner(", ", "[", "]");
        for (Entry entry : entries) {
            result.add(String.format(java.util.Locale.ROOT, "%s=%,d/%.1fms",
                    RocksDbNamespaces.diagnosticName((byte) entry.namespace()),
                    entry.gets(), entry.nanos() / 1_000_000.0d));
        }
        return result.toString();
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

    public void markMempoolUnbroadcast(Hash256 txid) {
        synchronized (chain) {
            synchronizePool();
            mempool.markUnbroadcast(txid);
            if (persistMempool) mempoolPersistenceDirty = true;
        }
    }

    public void acknowledgeMempoolBroadcast(Hash256 txidOrWtxid) {
        synchronized (chain) {
            synchronizePool();
            if (mempool.acknowledgeBroadcast(txidOrWtxid) && persistMempool) mempoolPersistenceDirty = true;
        }
    }

    public int unbroadcastMempoolCount() {
        synchronized (chain) { synchronizePool(); return mempool.unbroadcastTransactions().size(); }
    }

    public boolean isMempoolUnbroadcast(Hash256 txid) {
        synchronized (chain) { synchronizePool(); return mempool.isUnbroadcast(txid); }
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
    public UtxoSetInfo utxoSetInfo() { return utxoSetInfo(RocksDbUtxoStore.HashType.HASH_SERIALIZED_3); }

    public UtxoSetInfo utxoSetInfo(boolean includeHashSerialized3) {
        return utxoSetInfo(includeHashSerialized3 ? RocksDbUtxoStore.HashType.HASH_SERIALIZED_3 : RocksDbUtxoStore.HashType.NONE);
    }

    public UtxoSetInfo utxoSetInfo(RocksDbUtxoStore.HashType hashType) {
        synchronized (chain) {
            var stats = utxos.statistics(hashType);
            return new UtxoSetInfo(chain.activeTip().height(), chain.activeTip().hash(),
                    stats.transactions(), stats.txouts(), stats.bogoSize(), stats.diskSize(),
                    stats.totalAmount(), stats.hashSerialized3(), stats.muhash());
        }
    }

    public record TxOutInfo(long amount, byte[] scriptPubKey, long height, boolean coinbase, long confirmations) {
        public TxOutInfo { scriptPubKey = scriptPubKey.clone(); }
        @Override public byte[] scriptPubKey() { return scriptPubKey.clone(); }
    }
    public record UtxoSetInfo(long height, Hash256 bestBlock, long transactions, long txouts, long bogoSize,
                              long diskSize, long totalAmount, Hash256 hashSerialized3, Hash256 muhash) {}

    /** Writes a stable Bitcoin Core v2 UTXO snapshot of the current active chainstate. */
    public ru.bitcoin.node.storage.utxo.UtxoSnapshotWriter.Result dumpUtxoSnapshot(java.nio.file.Path path) throws java.io.IOException {
        synchronized (chain) {
            synchronizePool();
            BlockIndex tip = chain.activeTip();
            return ru.bitcoin.node.storage.utxo.UtxoSnapshotWriter.write(
                    database, utxos.namespacePrefix(), parameters.magic(), tip.hash(), tip.height(), path);
        }
    }

    /** Normal and snapshot chainstates, mirroring Core's getchainstates model. */
    public java.util.List<ChainStateInfo> chainStates() {
        synchronized (chain) {
            var snapshot = snapshotChainStateStore.load();
            if (snapshot.isEmpty()) {
                BlockIndex tip = chain.activeTip();
                return java.util.List.of(new ChainStateInfo(tip.height(), tip.hash(), false, true));
            }
            var state = snapshot.get();
            BlockIndex normal = lookup.find(state.normalTipHash());
            BlockIndex active = chain.activeTip();
            long normalHeight = normal == null ? state.normalTipHeight() : normal.height();
            var bg = assumeUtxoBackgroundValidator.state();
            boolean fullyValidated = bg != null && bg.status() == ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED;
            long backgroundHeight = bg == null ? normalHeight : bg.tipHeight();
            Hash256 backgroundHash = bg == null ? state.normalTipHash() : bg.tipHash();
            return java.util.List.of(
                    new ChainStateInfo(backgroundHeight, backgroundHash, false, true),
                    new ChainStateInfo(active.height(), active.hash(), true, fullyValidated));
        }
    }

    public record ChainStateInfo(long blocks, Hash256 bestBlockHash, boolean snapshot, boolean validated) {}

    /**
     * Stages and verifies a snapshot against the network's trusted AssumeUTXO commitment.
     * This deliberately does not activate it; active chainstate remains untouched.
     */
    public SnapshotVerification verifyUtxoSnapshot(java.nio.file.Path path) throws java.io.IOException {
        Objects.requireNonNull(path, "path");
        synchronized (chain) {
            ru.bitcoin.node.storage.utxo.UtxoSnapshotMetadata metadata;
            try (var in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path))) {
                metadata = ru.bitcoin.node.storage.utxo.UtxoSnapshotMetadata.read(in, parameters.magic());
            }
            var trusted = parameters.assumeUtxoForBlock(metadata.baseBlockHash())
                    .orElseThrow(() -> new java.io.IOException("Snapshot base block has no trusted AssumeUTXO commitment for " + parameters.network()));
            BlockIndex base = lookup.find(trusted.blockHash());
            if (base == null || base.height() != trusted.height())
                throw new java.io.IOException("Snapshot base block is not present in the local block index at the trusted height");
            var verified = new ru.bitcoin.node.storage.utxo.UtxoSnapshotVerifier(database, parameters).stageAndVerify(path, trusted);
            return new SnapshotVerification(trusted.height(), trusted.blockHash(), verified.coinsLoaded(),
                    verified.statistics().hashSerialized3(), trusted.chainTxCount());
        }
    }

    public record SnapshotVerification(long baseHeight, Hash256 baseHash, long coinsLoaded,
                                       Hash256 hashSerialized, long chainTxCount) {}

    /** Verifies and activates a trusted AssumeUTXO snapshot as the live chainstate. */
    public SnapshotVerification loadUtxoSnapshot(java.nio.file.Path path) throws java.io.IOException {
        Objects.requireNonNull(path, "path");
        synchronized (chain) {
            if (snapshotChainStateStore.load().isPresent())
                throw new java.io.IOException("A snapshot chainstate is already active");
            BlockIndex normalTip = chain.activeTip();
            SnapshotVerification verified = verifyUtxoSnapshot(path);
            BlockIndex snapshotTip = lookup.find(verified.baseHash());
            if (snapshotTip == null || snapshotTip.height() != verified.baseHeight()) {
                new ru.bitcoin.node.storage.utxo.UtxoSnapshotVerifier(database, parameters).clear();
                throw new java.io.IOException("Verified snapshot base disappeared from block index");
            }
            try {
                snapshotChainStateStore.activate(normalTip.hash(), normalTip.height(),
                        snapshotTip.hash(), snapshotTip.height());
                utxos.activateNamespace(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING);
                chain.activateTrustedSnapshot(normalTip, snapshotTip);
                poolTip = snapshotTip;
                mempool.revalidate(context(), point -> utxos.find(point).map(coin ->
                        new UtxoEntry(coin.amount(), coin.scriptPubKey(), coin.height(), coin.coinbase())), java.util.Set.of());
                initialBlockDownload.update(snapshotTip);
                revision++;
                chain.notifyAll();
                assumeUtxoBackgroundValidator.initializeIfNeeded();
                ensureBackgroundValidationWorker();
                return verified;
            } catch (RuntimeException e) {
                throw new java.io.IOException("Unable to activate verified snapshot", e);
            }
        }
    }

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

    public boolean coinStatsIndexEnabled() { return coinStatsIndexEnabled; }

    public Optional<UtxoSetInfo> indexedUtxoSetInfo(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        synchronized (chain) {
            if (!coinStatsIndexEnabled) return Optional.empty();
            synchronizeCoinStatsIndex();
            return coinStatsIndex.find(blockHash).map(stats -> new UtxoSetInfo(
                    stats.height(), stats.blockHash(), stats.transactions(), stats.txouts(),
                    stats.bogoSize(), 0L, stats.totalAmount(), null, stats.muhash()));
        }
    }

    public Optional<UtxoSetInfo> indexedUtxoSetInfo(long height) {
        synchronized (chain) {
            if (!coinStatsIndexEnabled || height < 0 || height > chain.activeTip().height()) return Optional.empty();
            BlockIndex index = activeAncestors.at(chain.activeTip(), height, lookup);
            return index == null ? Optional.empty() : indexedUtxoSetInfo(index.hash());
        }
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

    public boolean txOutSpenderIndexEnabled() { return txOutSpenderIndexEnabled; }

    /** Resolves an outpoint spender using mempool first and the optional active-chain index second. */
    public Optional<SpendingTransaction> spendingTransaction(OutPoint outPoint, boolean mempoolOnly) {
        Objects.requireNonNull(outPoint, "outPoint");
        synchronized (chain) {
            synchronizePool();
            Optional<Transaction> mempoolSpender = mempool.spendingTransaction(outPoint);
            if (mempoolSpender.isPresent()) return Optional.of(new SpendingTransaction(mempoolSpender.get(), null));
            if (mempoolOnly) return Optional.empty();
            if (!txOutSpenderIndexEnabled) throw new IllegalStateException(
                    "Mempool lacks a relevant spend, and txospenderindex is unavailable.");
            synchronizeTxOutSpenderIndex();
            var indexed = txOutSpenderIndexStore.find(outPoint).orElse(null);
            if (indexed == null) return Optional.empty();
            BlockIndex blockIndex = lookup.find(indexed.blockHash());
            if (blockIndex == null || blockIndex.height() > chain.activeTip().height()) return Optional.empty();
            BlockIndex active = activeAncestors.at(chain.activeTip(), blockIndex.height(), lookup);
            if (active == null || !active.hash().equals(indexed.blockHash())) return Optional.empty();
            Transaction transaction = blocks.find(indexed.blockHash()).flatMap(block -> block.transactions().stream()
                    .filter(tx -> tx.txId().equals(indexed.transactionId())).findFirst()).orElseThrow(() ->
                    new IllegalStateException("txospenderindex references missing spending transaction "+ indexed.transactionId().toDisplayHex()));
            return Optional.of(new SpendingTransaction(transaction, indexed.blockHash()));
        }
    }

    public record SpendingTransaction(Transaction transaction, Hash256 blockHash) {
        public SpendingTransaction { Objects.requireNonNull(transaction, "transaction"); }
        public boolean confirmed() { return blockHash != null; }
    }

    public boolean blockFilterIndexEnabled() { return blockFilterIndexEnabled; }

    public Optional<BlockFilterInfo> blockFilter(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        synchronized (chain) {
            if (!blockFilterIndexEnabled) return Optional.empty();
            synchronizeBlockFilterIndex();
            BlockIndex index = lookup.find(blockHash);
            if (index == null || index.height() > chain.activeTip().height()) return Optional.empty();
            BlockIndex active = activeAncestors.at(chain.activeTip(), index.height(), lookup);
            if (active == null || !active.hash().equals(blockHash)) return Optional.empty();
            return blockFilterIndex.find(blockHash).map(record -> new BlockFilterInfo(record.filter(), record.header()));
        }
    }

    public record BlockFilterInfo(byte[] filter, Hash256 header) {
        public BlockFilterInfo {
            filter = Objects.requireNonNull(filter, "filter").clone();
            Objects.requireNonNull(header, "header");
        }
        @Override public byte[] filter() { return filter.clone(); }
    }

    private void synchronizeBlockFilterIndex() {
        if (blockFilterIndexEnabled) blockFilterIndex.synchronize(chain.activeTip());
    }

    private void synchronizeCoinStatsIndex() {
        if (coinStatsIndexEnabled) coinStatsIndex.synchronize(chain.activeTip());
    }

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

    private void synchronizeTxOutSpenderIndex() {
        if (!txOutSpenderIndexEnabled) return;
        BlockIndex activeTip = chain.activeTip();
        Hash256 indexedHash = txOutSpenderIndexStore.bestIndexedBlockHash().orElse(null);
        if (indexedHash == null) {
            BlockIndex genesis = activeAncestors.at(activeTip, 0L, lookup);
            if (genesis == null) throw new IllegalStateException("Cannot initialize txospenderindex without genesis");
            txOutSpenderIndexStore.initializeAt(genesis.hash());
            indexedHash = genesis.hash();
        }
        BlockIndex indexed = lookup.find(indexedHash);
        if (indexed == null) throw new IllegalStateException(
                "txospenderindex cursor references unknown block: " + indexedHash.toDisplayHex());
        ReorganizationPlan plan = ReorganizationPlanner.plan(indexed, activeTip, lookup);
        for (BlockIndex disconnect : plan.blocksToDisconnect()) {
            Block block = blocks.find(disconnect.hash()).orElseThrow(() ->
                    new IllegalStateException("Block body required to rewind txospenderindex: " + disconnect.hash().toDisplayHex()));
            txOutSpenderIndexStore.rewind(block, disconnect.previousBlockHash());
        }
        for (BlockIndex connect : plan.blocksToConnect()) {
            Block block = blocks.find(connect.hash()).orElseThrow(() ->
                    new IllegalStateException("Block body required to synchronize txospenderindex: " + connect.hash().toDisplayHex()));
            txOutSpenderIndexStore.append(block);
        }
        Hash256 synchronizedHash = txOutSpenderIndexStore.bestIndexedBlockHash().orElseThrow();
        if (!synchronizedHash.equals(activeTip.hash())) throw new IllegalStateException(
                "txospenderindex synchronization stopped at " + synchronizedHash.toDisplayHex()
                        + " instead of active tip " + activeTip.hash().toDisplayHex());
    }

    public long prioritiseTransaction(Hash256 txid, long feeDelta) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            long result = mempool.prioritise(txid, feeDelta);
            if (persistMempool) mempoolFeeDeltaStore.replace(mempool.prioritisedTransactions());
            revision++; chain.notifyAll();
            return result;
        }
    }

    public Map<Hash256, Long> prioritisedTransactions() {
        synchronized (chain) { return mempool.prioritisedTransactions(); }
    }

    public List<MempoolEntry> mempoolEntries() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.entries();
        }
    }

    public Mempool.Snapshot mempoolSnapshot() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.snapshot();
        }
    }

    public Mempool.DetailedSnapshot detailedMempoolSnapshot() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.detailedSnapshot();
        }
    }

    public Optional<Mempool.GraphQuery> mempoolAncestorQuery(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.ancestorQuery(txid);
        }
    }

    public Optional<Mempool.GraphQuery> mempoolDescendantQuery(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.descendantQuery(txid);
        }
    }

    public Map<Hash256, Mempool.EntryGraphView> mempoolGraphEntries(Collection<Hash256> txids) {
        Objects.requireNonNull(txids, "txids");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.graphViews(txids);
        }
    }

    public Optional<Mempool.ClusterView> mempoolCluster(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.cluster(txid);
        }
    }

    public Optional<Mempool.EntryGraphView> mempoolGraphEntry(Hash256 txid) {
        Objects.requireNonNull(txid, "txid");
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.graphView(txid);
        }
    }

    public List<ClusterLinearization.Chunk> mempoolFeeRateDiagram() {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            return mempool.feeRateDiagram();
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

    /** Non-mutating mempool/package acceptance probe used by testmempoolaccept. */
    public Mempool.TestAcceptResult testMempoolAccept(List<Transaction> transactions) {
        synchronized (chain) {
            synchronizePool();
            return mempool.testAccept(List.copyOf(transactions), context(), coins);
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
            BlockIndex candidate = failureManager.bestEligibleAfterInvalidating(hash);
            BlockIndex current = chain.activeTip();

            if (current.hash().equals(candidate.hash())) {
                // Invalidating a non-active branch needs no chain transition.
                failureManager.markFailed(hash);
            } else {
                ReorganizationPlan plan = ReorganizationPlanner.plan(current, candidate, lookup);
                PreparedChainReorganization prepared = reorganizationExecutor.prepare(
                        new ChainUpdate(current, candidate, plan));

                // Preparation above performs all body/undo loading and contextual/script
                // validation before the administrative failure root becomes durable.
                // The failure root, best-header pointer and chain transition are then
                // committed in one RocksDB batch.
                reorganizationExecutor.commit(prepared, batch ->
                        failureManager.appendInvalidation(batch, hash, candidate));
            }
            finishManualChainChange();
        }
    }

    /** Clear related failure roots and atomically activate the strongest available eligible chain. */
    public void reconsiderBlock(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        synchronized (chain) {
            synchronizePool();
            if (lookup.find(hash) == null) throw new IllegalArgumentException("Block not found");

            BlockFailureManager.ReconsiderationPlan reconsideration =
                    failureManager.prepareReconsideration(
                            hash,
                            candidate -> blocks.find(candidate.hash()).isPresent());
            BlockIndex current = chain.activeTip();
            BlockIndex candidate = reconsideration.bestAvailable();

            if (current.hash().equals(candidate.hash())) {
                failureManager.commitReconsideration(reconsideration);
            } else {
                ReorganizationPlan plan = ReorganizationPlanner.plan(current, candidate, lookup);
                PreparedChainReorganization prepared = reorganizationExecutor.prepare(
                        new ChainUpdate(current, candidate, plan));

                // No persistent failure root is cleared until every required block body,
                // undo record and contextual/script check for the forced transition has
                // succeeded. Administrative metadata and the chain transition then share
                // the same RocksDB batch.
                reorganizationExecutor.commit(prepared, batch ->
                        failureManager.appendReconsideration(batch, reconsideration));
            }
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
        if (txOutSpenderIndexEnabled) synchronizeTxOutSpenderIndex();
        if (coinStatsIndexEnabled) synchronizeCoinStatsIndex();
        if (blockFilterIndexEnabled) synchronizeBlockFilterIndex();
        initialBlockDownload.update(chain.activeTip());
        revision++;
        chain.notifyAll();
    }

    public boolean downloadPruningEnabled() { return blockPruner.enabled(); }

    public boolean downloadInitialBlockDownload() {
        return initialBlockDownload.isInitialBlockDownload();
    }

    public boolean downloadHasMinimumChainWork() {
        return chain.activeTip().chainWork().compareTo(parameters.minimumChainWork()) >= 0;
    }

    public BlockIndex downloadSnapshotBase() {
        var snapshot = snapshotChainStateStore.load();
        if (snapshot.isEmpty()) return null;
        var background = assumeUtxoBackgroundValidator.state();
        if (background != null && background.status() == ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED)
            return null;
        return lookup.find(snapshot.get().snapshotBaseHash());
    }

    /** Immutable committed tip snapshot for network scheduling; does not wait for a validation batch. */
    public BlockIndex downloadTip() {
        return chain.activeTip();
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
            if (known != null) {
                if (failureManager.isFailed(known.hash()))
                    return new ProposalResult(false, "duplicate-invalid");
                if (validationStatus.isScriptsValid(known.hash()))
                    return new ProposalResult(false, "duplicate");
                return new ProposalResult(false, "duplicate-inconclusive");
            }

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
        return createMiningTemplate(payout, extraNonce, maximumWeight, minimumRate, null);
    }

    private Block createMiningTemplate(
            byte[] payout, byte[] extraNonce, long maximumWeight, FeeRate minimumRate, Set<String> supportedRules) {
        synchronized (chain) {
            synchronizePool();
            expirePersistent();
            var parent = chain.activeTip();
            long now = time.currentTimeSeconds();
            long timestamp = Math.max(now, ChainHeaderValidator.minimumTimestamp(
                    parent, MedianTimePast.calculate(parent, lookup), parameters));
            if (timestamp > Math.addExact(now, 7200))
                throw new IllegalStateException("Chain time too far ahead of local time");
            var blockTime = new ru.bitcoin.node.common.types.UInt32(timestamp);
            var bits = ChainHeaderValidator.nextBits(parent, lookup, parameters, blockTime);
            int version = ru.bitcoin.node.mining.MiningVersionBits.preferredVersion(
                    0x20000000, miningDeployments(parent), supportedRules);
            return ru.bitcoin.node.mining.BlockTemplateBuilder.fromMempool(parent, lookup, utxos, parameters,
                    version, blockTime, bits, payout, extraNonce, mempool.entries(), maximumWeight, minimumRate);
        }
    }

    private List<ru.bitcoin.node.mining.MiningVersionBits.DeploymentView> miningDeployments(BlockIndex parent) {
        return List.of(new ru.bitcoin.node.mining.MiningVersionBits.DeploymentView(
                "taproot", 2, TaprootDeployment.stateForNextBlock(parent, lookup, parameters), true));
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

    private List<PersistedMempoolEntry> persistedEntries(List<MempoolEntry> entries) {
        return entries.stream()
                .map(entry -> new PersistedMempoolEntry(entry.transaction(), entry.arrivalTime(), entry.admissionHeight(), mempool.isUnbroadcast(entry.transaction().txId())))
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
            mempoolFeeDeltaStore.replace(mempool.prioritisedTransactions());
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
                        long height = persisted.admissionHeight() == Long.MAX_VALUE
                                ? Math.max(0L, context().nextBlockHeight() - 1L)
                                : persisted.admissionHeight();
                        mempool.admitRestored(tx, persisted.arrivalTime(), height, persisted.unbroadcast(), context(), coins);
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

    /** Attaches the ordinary P2P block scheduler to AssumeUTXO historical validation. */
    public void attachBackgroundBlockDownloadScheduler(ru.bitcoin.node.p2p.sync.BlockDownloadScheduler scheduler) {
        this.backgroundBlockDownloadScheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        ensureBackgroundValidationWorker();
    }

    private static final int ASSUMEUTXO_BACKGROUND_DOWNLOAD_WINDOW = 32;

    private boolean fetchMissingBackgroundBlocks() {
        var scheduler = backgroundBlockDownloadScheduler;
        var missing = assumeUtxoBackgroundValidator.missingBlocks(ASSUMEUTXO_BACKGROUND_DOWNLOAD_WINDOW);
        if (scheduler == null || missing.isEmpty()) return false;
        java.util.Map<Hash256, AssumeUtxoBackgroundValidator.MissingBlock> expected = new java.util.HashMap<>();
        java.util.List<ru.bitcoin.node.p2p.sync.BlockDownloadRequest> requests = new java.util.ArrayList<>(missing.size());
        for (var item : missing) {
            expected.put(item.hash(), item);
            requests.add(new ru.bitcoin.node.p2p.sync.BlockDownloadRequest(item.hash(), item.height()));
        }
        boolean storedAny = false;
        try (var session = scheduler.openSession()) {
            session.submitRequests(requests);
            while (session.pendingCount() > 0) {
                var completed = session.awaitCompleted();
                var item = expected.get(completed.requestedHash());
                if (item == null) continue;
                try {
                    assumeUtxoBackgroundValidator.storeDownloadedBlock(item, completed.block());
                    storedAny = true;
                } catch (ru.bitcoin.node.consensus.block.BlockValidationException | IllegalArgumentException badBody) {
                    if (completed.sourcePeer() != null) {
                        try { completed.sourcePeer().close(); } catch (java.io.IOException closeFailure) { badBody.addSuppressed(closeFailure); }
                    }
                }
            }
        } catch (java.io.IOException transientFailure) {
            return storedAny;
        }
        return storedAny;
    }

    /** Current historical AssumeUTXO validation progress, if a snapshot is active. */
    public java.util.Optional<BackgroundValidationInfo> backgroundValidationInfo() {
        var s = assumeUtxoBackgroundValidator.state();
        if (s == null) return java.util.Optional.empty();
        return java.util.Optional.of(new BackgroundValidationInfo(s.status().name().toLowerCase(java.util.Locale.ROOT), s.tipHeight(), s.tipHash()));
    }

    public record BackgroundValidationInfo(String status, long height, Hash256 bestBlockHash) {}

    private synchronized void ensureBackgroundValidationWorker() {
        if (snapshotChainStateStore.load().isEmpty() || backgroundValidationStop) return;
        Thread existing = backgroundValidationThread;
        if (existing != null && existing.isAlive()) return;
        Thread worker = new Thread(() -> {
            while (!backgroundValidationStop) {
                try {
                    AssumeUtxoBackgroundValidator.Step step;
                    synchronized (chain) {
                        step = assumeUtxoBackgroundValidator.step();
                        if (blockPruner.automatic() && (step == AssumeUtxoBackgroundValidator.Step.ADVANCED
                                || step == AssumeUtxoBackgroundValidator.Step.VALIDATED))
                            blockPruner.prune(chain.activeTip(), assumeUtxoPruneCeiling());
                    }
                    if (step == AssumeUtxoBackgroundValidator.Step.INACTIVE
                            || step == AssumeUtxoBackgroundValidator.Step.VALIDATED
                            || step == AssumeUtxoBackgroundValidator.Step.INVALID) return;
                    if (step == AssumeUtxoBackgroundValidator.Step.WAITING_FOR_BLOCK) {
                        if (!fetchMissingBackgroundBlocks()) Thread.sleep(1000L);
                    } else Thread.yield();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // Preserve durable progress. A transient storage/body condition can be retried after restart.
                    return;
                }
            }
        }, "assumeutxo-background-validation");
        worker.setDaemon(true);
        backgroundValidationThread = worker;
        worker.start();
    }

    @Override
    public synchronized void close() {
        initialSyncPrefetcher.close();
        database.forceFlushChainstate();
        database.disableChainstateWriteBack(false);
        backgroundValidationStop = true;
        Thread worker = backgroundValidationThread;
        if (worker != null) worker.interrupt();
    }

}
