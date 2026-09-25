package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.FullReindexer;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class FullReindexIntegrationTest {
    @TempDir Path directory;

    @Test
    void rebuildsIndexesAndChainstateFromBitcoinCoreRawBlocks() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var time = (ru.bitcoin.node.consensus.time.AdjustedTime) () -> 1_800_000_000L;
        ru.bitcoin.node.common.types.Hash256 expectedTip;

        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, parameters, time, new Mempool());
            for (int height = 1; height <= 3; height++) {
                try (var in = Objects.requireNonNull(
                        getClass().getResourceAsStream("/core-regtest/" + height + ".bin"))) {
                    validation.processBlock(BlockParser.parse(in.readAllBytes()));
                }
            }
            expectedTip = validation.activeTip().hash();
            assertEquals(3L, validation.activeTip().height());

            // Simulate block-index damage while leaving raw block bodies intact.
            new RocksDbBlockIndexStore(db).delete(expectedTip);

            var result = new FullReindexer(db, parameters, time).rebuild();
            assertEquals(expectedTip, result.activeTipHash());
            assertEquals(3L, result.activeHeight());
            assertEquals(3L, result.replayedBlocks());
            assertEquals(4L, result.discoveredBlockBodies());
        }

        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, parameters, time, new Mempool());
            assertEquals(expectedTip, validation.activeTip().hash());
            assertEquals(3L, validation.activeTip().height());
        }
    }
}
