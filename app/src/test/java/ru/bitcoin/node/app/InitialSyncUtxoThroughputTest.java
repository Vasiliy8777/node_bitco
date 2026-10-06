package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.*;
import ru.bitcoin.node.storage.utxo.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same input-heavy chain with/without hints; timing is diagnostic, never an assertion. */
class InitialSyncUtxoThroughputTest {
    @TempDir Path directory;

    @Test
    @EnabledIfSystemProperty(named = "ibd.benchmark.utxo", matches = "true")
    void comparesInputHeavyConnectionWithIdenticalColdNativeCaches() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        int count = 64, inputsPerBlock = 1024, coinsCount = 262144;
        var points = new ArrayList<OutPoint>();
        for (int i = 0; i < coinsCount; i++) {
            byte[] seed = java.nio.ByteBuffer.allocate(4).putInt(i).array();
            points.add(new OutPoint(Hash256Digest.hash(seed), new UInt32(0)));
        }
        var blocks = new ArrayList<Block>();
        var indexes = new ArrayList<BlockIndex>();
        var tip = BlockIndexFactory.createGenesis(GenesisBlockFactory.create(parameters).header());
        for (int height = 1; height <= count; height++) {
            var inputs = new ArrayList<TxIn>();
            for (int j = 0; j < inputsPerBlock; j++) {
                inputs.add(new TxIn(points.get((height - 1) * inputsPerBlock + j), new byte[0], TxIn.FINAL_SEQUENCE));
            }
            var spend = new Transaction(2, inputs,
                    List.of(new TxOut(inputsPerBlock * 1000L - 1000, new byte[]{0x51})), new UInt32(0));
            var coinbase = CoinbaseBuilder.build(height, parameters, 1000, new byte[]{0x51}, new byte[]{0}, List.of(spend));
            var transactions = List.of(coinbase, spend);
            var root = MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList());
            for (long nonce = 0; ; nonce++) {
                var header = new BlockHeader(4, tip.hash(), root, new UInt32(tip.header().timestamp().value() + 1),
                        new UInt32(0x207fffffL), new UInt32(nonce));
                if (!ProofOfWork.isValid(header, parameters)) continue;
                blocks.add(new Block(header, transactions));
                tip = BlockIndexFactory.createChild(tip, header);
                indexes.add(tip);
                break;
            }
        }
        // Alternate order in a second round to expose filesystem-cache/order effects.
        for (int round = 0; round < 2; round++) for (boolean enabled : round == 0
                ? new boolean[]{false, true} : new boolean[]{true, false}) {
            Path path = directory.resolve("run-" + round + "-" + enabled);
            try (var db = new RocksDbDatabase(path, 0, 8)) {
                try (var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool())) {
                    var store = new RocksDbUtxoStore(db);
                    for (int offset = 0; offset < coinsCount; offset += 4096) {
                        try (var batch = new RocksDbWriteBatch()) {
                            for (int i = offset; i < Math.min(coinsCount, offset + 4096); i++) {
                                store.save(batch, points.get(i), new StoredUtxo(1000, new byte[]{0x51}, 0, false));
                            }
                            db.write(batch);
                        }
                    }
                    new KnownHeaderStorage(db, new RocksDbBlockIndexStore(db), new RocksDbChainStateStore(db))
                            .saveBatch(indexes, tip);
                }
            }
            // Materialize SSTs so the fixture measures persistent reads, not replayed memtables.
            try (var options = new org.rocksdb.Options(); var nativeDb = org.rocksdb.RocksDB.open(options, path.toString());
                 var flush = new org.rocksdb.FlushOptions().setWaitForFlush(true)) { nativeDb.flush(flush); }
            try (var db = new RocksDbDatabase(path, 0, 8);
                 var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool())) {
                service.initialSyncPrefetchEnabled(enabled);
                long started = System.nanoTime();
                for (int i = 0; i < blocks.size(); i += 2) {
                    service.prefetchInitialSyncInputsAhead(blocks.subList(Math.min(i + 2, count), Math.min(i + 6, count)));
                    assertEquals(List.of(BlockProcessingResult.CONNECTED, BlockProcessingResult.CONNECTED),
                            service.processInitialSyncBatch(blocks.subList(i, i + 2)));
                }
                double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                assertEquals(tip.hash(), service.activeTip().hash());
                var coins = new RocksDbUtxoStore(db);
                assertTrue(coins.find(points.getFirst()).isEmpty());
                assertTrue(coins.find(points.get(count * inputsPerBlock)).isPresent());
                System.out.printf(Locale.ROOT,
                        "IBD_UTXO_BENCH round=%d prefetch=%s inputs=%d seconds=%.3f blocks/s=%.1f warmKeys=%d%n",
                        round, enabled, count * inputsPerBlock, seconds, count / seconds, db.warmReadStats().keys());
            }
        }
    }
}
