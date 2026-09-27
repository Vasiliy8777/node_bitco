package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class CoinStatsIndexIntegrationTest {
    @TempDir Path directory;

    @Test
    void indexesHistoricalMuHashAndRecoversAcrossRestart() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        List<Block> blocks = new ArrayList<>();
        List<NodeValidationService.UtxoSetInfo> expected = new ArrayList<>();

        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, false, true);
            for (int height = 1; height <= 3; height++) {
                Block block = BlockParser.parse(testResource("/core-spend/" + height + ".bin"));
                assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(block));
                blocks.add(block);
                var direct = service.utxoSetInfo(RocksDbUtxoStore.HashType.MUHASH);
                var indexed = service.indexedUtxoSetInfo(block.hash()).orElseThrow();
                assertStatsEqual(direct, indexed);
                expected.add(indexed);
            }
        }

        try (var db = new RocksDbDatabase(directory)) {
            var restarted = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, false, true);
            for (int i = 0; i < blocks.size(); i++) {
                var indexed = restarted.indexedUtxoSetInfo(blocks.get(i).hash()).orElseThrow();
                assertStatsEqual(expected.get(i), indexed);
            }
        }
    }

    @Test
    void followsReorganizationAndRetainsDetachedHistoricalRecord() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, false, true);
            Block oldTip = null;
            for (int height = 1; height <= 102; height++) {
                oldTip = BlockParser.parse(testResource("/core-spend/" + height + ".bin"));
                assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(oldTip));
            }
            var detachedStats = service.indexedUtxoSetInfo(oldTip.hash()).orElseThrow();
            Block alternative102 = BlockParser.parse(testResource("/core-reorg/102.bin"));
            Block alternative103 = BlockParser.parse(testResource("/core-reorg/103.bin"));
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, service.processBlock(alternative102));
            assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(alternative103));
            assertEquals(alternative103.hash(), service.activeTip().hash());
            assertStatsEqual(service.utxoSetInfo(RocksDbUtxoStore.HashType.MUHASH),
                    service.indexedUtxoSetInfo(alternative103.hash()).orElseThrow());
            assertEquals(detachedStats.muhash(), service.indexedUtxoSetInfo(oldTip.hash()).orElseThrow().muhash());
        }
    }

    private static void assertStatsEqual(NodeValidationService.UtxoSetInfo expected,
                                         NodeValidationService.UtxoSetInfo actual) {
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.bestBlock(), actual.bestBlock());
        assertEquals(expected.transactions(), actual.transactions());
        assertEquals(expected.txouts(), actual.txouts());
        assertEquals(expected.bogoSize(), actual.bogoSize());
        assertEquals(expected.totalAmount(), actual.totalAmount());
        assertEquals(expected.muhash(), actual.muhash());
    }

    private byte[] testResource(String name) throws Exception {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream(name))) {
            return in.readAllBytes();
        }
    }
}
