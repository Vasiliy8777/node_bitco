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
    private BlockIndex poolTip;

    public NodeValidationService(RocksDbDatabase database, NetworkParameters parameters,
                                 AdjustedTime time, Mempool mempool) {
        this.mempool = Objects.requireNonNull(mempool);
        var utxos = new RocksDbUtxoStore(database);
        var indexes = new RocksDbBlockIndexStore(database);
        var tips = new RocksDbChainStateStore(database);
        var undos = new RocksDbUndoStore(database);
        blocks = new RocksDbBlockStore(database);
        lookup = new StoredBlockIndexLookup(indexes);
        chain = new ChainInitializer(database, parameters).initialize();
        var storage = new RocksDbChainTransitionStorage(database, utxos, undos, indexes, tips);
        var executor = new ChainReorganizationExecutor(blocks, undos, utxos,
                new ChainTransitionManager(chain, storage), parameters, lookup);
        processor = new BlockProcessor(chain, lookup, new KnownBlockStorage(database, blocks, indexes), executor, parameters, time);
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
            return mempool.admit(transaction, context(), coins);
        }
    }
    public List<MempoolEntry> mempoolEntries() {
        synchronized (chain) { synchronizePool(); mempool.expire(); return mempool.entries(); }
    }
    public List<MempoolEntry> admitPackage(List<Transaction> transactions) {
        synchronized (chain) {
            synchronizePool();
            mempool.expire();
            return mempool.admitPackage(transactions, context(), coins);
        }
    }
    public BlockIndex activeTip() { synchronized (chain) { return chain.activeTip(); } }

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
    }
    private Block requireBlock(BlockIndex index) {
        return blocks.find(index.hash()).orElseThrow(() -> new IllegalStateException("Missing chain-update block: " + index.hash()));
    }
}
