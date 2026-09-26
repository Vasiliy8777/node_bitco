package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TxIndexIntegrationTest {
    @TempDir Path directory;

    @Test
    void indexesConnectedTransactionsAndRecoversCursorAcrossRestart() {
        var parameters = NetworkParametersRegistry.regtest();
        Block mined;
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, true);
            long height = 1;
            var coinbase = CoinbaseBuilder.build(height, parameters, 0L, new byte[]{0x51}, new byte[8], List.of());
            var parent = service.activeTip();
            var header = new BlockHeader(0x20000000, parent.hash(),
                    MerkleTree.calculateRoot(List.of(coinbase.txId())), new UInt32(1_800_000_000L),
                    parent.header().bits(), new UInt32(0));
            mined = NonceMiner.search(new Block(header, List.of(coinbase)), parameters, 0, 100_000, () -> false).orElseThrow();
            assertEquals(ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED, service.processBlock(mined));
            var indexed = service.indexedTransaction(coinbase.txId()).orElseThrow();
            assertEquals(mined.hash(), indexed.blockInfo().index().hash());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var restarted = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, true);
            assertEquals(mined.hash(), restarted.indexedTransaction(mined.transactions().getFirst().txId())
                    .orElseThrow().blockInfo().index().hash());
        }
    }
}
