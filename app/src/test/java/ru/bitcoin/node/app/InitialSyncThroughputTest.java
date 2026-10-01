package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline IBD measurement: excludes block generation and header download. No timing assertion. */
class InitialSyncThroughputTest {
    @TempDir Path directory;

    @Test
    void connectsPreloadedHeadersInBatches() {
        var parameters = NetworkParametersRegistry.regtest();
        int count = Integer.getInteger("ibd.benchmark.blocks", 4096);
        int batchSize = Integer.getInteger("ibd.benchmark.batch", 512);
        assertTrue(count > 0, "ibd.benchmark.blocks must be positive");
        assertTrue(batchSize > 0, "ibd.benchmark.batch must be positive");
        var blocks = new ArrayList<Block>();
        var indexes = new ArrayList<BlockIndex>();
        var parent = BlockIndexFactory.createGenesis(GenesisBlockFactory.create(parameters).header());
        for (int height = 1; height <= count; height++) {
            var coinbase = CoinbaseBuilder.build(height, parameters, 0, new byte[]{0x51},
                    new byte[]{0}, List.of());
            for (long nonce = 0; ; nonce++) {
                var header = new BlockHeader(4, parent.hash(), coinbase.txId(),
                        new UInt32(parent.header().timestamp().value() + 1),
                        new UInt32(0x207fffffL), new UInt32(nonce));
                if (!ProofOfWork.isValid(header, parameters)) continue;
                blocks.add(new Block(header, List.of(coinbase)));
                parent = BlockIndexFactory.createChild(parent, header);
                indexes.add(parent);
                break;
            }
        }
        try (var database = new RocksDbDatabase(directory.resolve("ibd"))) {
            var service = new NodeValidationService(database, parameters, () -> 1_800_000_000L, new Mempool());
            new KnownHeaderStorage(database, new RocksDbBlockIndexStore(database),
                    new RocksDbChainStateStore(database)).saveBatch(indexes, parent);
            long started = System.nanoTime();
            for (int offset = 0; offset < count; offset += batchSize) {
                var results = service.processInitialSyncBatch(blocks.subList(offset, Math.min(count, offset + batchSize)));
                assertTrue(results.stream().allMatch(result -> result == BlockProcessingResult.CONNECTED));
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "IBD_BENCH blocks=%d batch=%d seconds=%.3f blocks/s=%.1f%n",
                    count, batchSize, seconds, count / seconds);
            assertEquals(parent.hash(), service.activeTip().hash());
            assertEquals(count, service.activeTip().height());
        }
        try (var database = new RocksDbDatabase(directory.resolve("ibd"))) {
            var reopened = new NodeValidationService(database, parameters, () -> 1_800_000_000L, new Mempool());
            assertEquals(parent.hash(), reopened.activeTip().hash());
        }
    }
}
