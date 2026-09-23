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
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import java.util.*;

/** Application entry point for block and transaction admission. The caller owns
 * the database lifetime and must not mutate these stores through another processor.
 */
public final class NodeValidationService {
    private final ChainState chain;
    private final BlockIndexLookup lookup;
    private final RocksDbBlockStore blocks;
    private final UtxoView coins;
    private final BlockProcessor processor;
    private final Mempool mempool;
    private final NetworkParameters parameters;
    private final AdjustedTime time;
    private final RocksDbUtxoStore utxos;
    private BlockIndex poolTip;
    private long revision;

    public long revision() { synchronized (chain) { synchronizePool(); return revision; } }

    public record MiningSnapshot(Block block, List<MempoolEntry> entries, long height, long medianTimePast, long revision) { }

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

    public Optional<Block> findBlock(Hash256 hash) {
        synchronized (chain) {
            var candidate = lookup.find(hash);
            if (candidate == null) return Optional.empty();
            var cursor = chain.activeTip();
            while (cursor.height() > candidate.height())
                cursor = Objects.requireNonNull(lookup.find(cursor.previousBlockHash()), "Missing active ancestor");
            return cursor.hash().equals(hash) ? blocks.find(hash) : Optional.empty();
        }
    }

    /** Only returns active-chain headers, in forward order, bounded to the wire limit. */
    public List<ru.bitcoin.node.protocol.block.BlockHeader> headers(List<Hash256> locator, Hash256 stop) {
        synchronized (chain) {
            Set<Hash256> wanted = new HashSet<>(locator);
            var result = new ArrayDeque<ru.bitcoin.node.protocol.block.BlockHeader>();
            var cursor = chain.activeTip();
            while (cursor.height() > 0 && !wanted.contains(cursor.hash())) {
                result.addFirst(cursor.header());
                if (result.size() > 2000) result.removeLast();
                cursor = Objects.requireNonNull(lookup.find(cursor.previousBlockHash()), "Missing active ancestor");
            }
            var answer = new ArrayList<ru.bitcoin.node.protocol.block.BlockHeader>();
            for (var header : result) {
                answer.add(header);
                if (header.hash().equals(stop)) break;
            }
            return List.copyOf(answer);
        }
    }

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool) {
        this.mempool = Objects.requireNonNull(mempool);
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
        var storage = new RocksDbChainTransitionStorage(database, utxos, undos, indexes, tips);
        var executor = new ChainReorganizationExecutor(
                blocks,
                undos,
                utxos,
                new ChainTransitionManager(chain, storage),
                parameters,
                lookup,
                failureManager::markFailed
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
        synchronized (chain) { mempool.revalidate(context(), coins, Set.of()); }
    }

    public BlockProcessingResult processBlock(Block block) {
        synchronized (chain) {
            synchronizePool();
            BlockProcessingResult result = processor.process(block);
            // Chain storage has committed. On a history/storage failure poolTip stays old,
            // and every later API call retries synchronization before exposing the pool.
            synchronizePool();
            return result;
        }
    }
    public MempoolEntry admit(Transaction transaction) {
        synchronized (chain) {
            synchronizePool();
            mempool.expire();
            var entry = mempool.admit(transaction, context(), coins);
            revision++;
            chain.notifyAll();
            return entry;
        }
    }
    public List<MempoolEntry> mempoolEntries() {
        synchronized (chain) { synchronizePool(); mempool.expire(); return mempool.entries(); }
    }
    public List<MempoolEntry> admitPackage(List<Transaction> transactions) {
        synchronized (chain) {
            synchronizePool();
            mempool.expire();
            var entries = mempool.admitPackage(transactions, context(), coins);
            revision++;
            chain.notifyAll();
            return entries;
        }
    }
    public BlockIndex activeTip() { synchronized (chain) { return chain.activeTip(); } }

    /** Fresh template from one coherent chain/mempool snapshot. Does not search PoW. */
    public Block createMiningTemplate(byte[] payout, byte[] extraNonce, long maximumWeight, FeeRate minimumRate) {
        synchronized (chain) {
            synchronizePool();
            mempool.expire();
            var parent = chain.activeTip();
            long now = time.currentTimeSeconds();
            long timestamp = Math.max(now, Math.addExact(MedianTimePast.calculate(parent,lookup),1));
            if (timestamp > Math.addExact(now,7200)) throw new IllegalStateException("Chain time too far ahead of local time");
            var blockTime = new ru.bitcoin.node.common.types.UInt32(timestamp);
            var bits = ChainHeaderValidator.nextBits(parent,lookup,parameters,blockTime);
            return ru.bitcoin.node.mining.BlockTemplateBuilder.fromMempool(parent,lookup,utxos,parameters,
                    0x20000000,blockTime,bits,payout,extraNonce,mempool.entries(),maximumWeight,minimumRate);
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
        mempool.reconcile(context(), coins, confirmed, retry);
        poolTip = tip;
        revision++;
        chain.notifyAll();
    }
    private Block requireBlock(BlockIndex index) {
        return blocks.find(index.hash()).orElseThrow(() -> new IllegalStateException("Missing chain-update block: " + index.hash()));
    }
}
