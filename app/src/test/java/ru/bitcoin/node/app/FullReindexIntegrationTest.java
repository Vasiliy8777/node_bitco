package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.FullReindexer;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.txindex.RocksDbTxIndexStore;

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
        ru.bitcoin.node.common.types.Hash256 indexedTxid;

        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(
                    db, parameters, time, new Mempool(), 0L, parameters.defaultAssumeValid(), false, true);
            ru.bitcoin.node.protocol.block.Block lastBlock = null;
            for (int height = 1; height <= 3; height++) {
                try (var in = Objects.requireNonNull(
                        getClass().getResourceAsStream("/core-regtest/" + height + ".bin"))) {
                    lastBlock = BlockParser.parse(in.readAllBytes());
                    validation.processBlock(lastBlock);
                }
            }
            indexedTxid = Objects.requireNonNull(lastBlock).transactions().getFirst().txId();
            expectedTip = validation.activeTip().hash();
            assertEquals(expectedTip, new RocksDbTxIndexStore(db).findBlockHash(indexedTxid).orElseThrow());
            assertEquals(3L, validation.activeTip().height());

            // Simulate block-index damage while leaving raw block bodies intact.
            new RocksDbBlockIndexStore(db).delete(expectedTip);

            var result = new FullReindexer(db, parameters, time).rebuild();
            assertEquals(expectedTip, result.activeTipHash());
            assertEquals(3L, result.activeHeight());
            assertEquals(3L, result.replayedBlocks());
            assertEquals(4L, result.discoveredBlockBodies());
            // Full reindex must atomically discard the pre-reindex txindex cursor/mappings.
            var txIndex = new RocksDbTxIndexStore(db);
            assertTrue(txIndex.bestIndexedBlockHash().isEmpty());
            assertTrue(txIndex.findBlockHash(indexedTxid).isEmpty());
        }

        try (var db = new RocksDbDatabase(directory)) {
            // Enabling txindex after the rebuild must reconstruct it from genesis to the new active tip.
            var validation = new NodeValidationService(
                    db, parameters, time, new Mempool(), 0L, parameters.defaultAssumeValid(), false, true);
            assertEquals(expectedTip, validation.activeTip().hash());
            assertEquals(3L, validation.activeTip().height());
            var txIndex = new RocksDbTxIndexStore(db);
            assertEquals(expectedTip, txIndex.bestIndexedBlockHash().orElseThrow());
            assertEquals(expectedTip, txIndex.findBlockHash(indexedTxid).orElseThrow());
        }
    }
}
