package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.*;
import ru.bitcoin.node.storage.chain.RocksDbPruneStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockPrunerTest {
    @TempDir Path temp;

    @Test void prunesOnlyBelowSafetyWindowAndKeepsIndexes() {
        var params = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesisBlock = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesisBlock.header());
            blocks.save(genesisBlock); indexes.save(BlockIndexStorageMapper.toStored(parent));
            Block[] chain = new Block[4];
            BlockIndex[] idx = new BlockIndex[4];
            for (int i=0;i<4;i++) {
                var h = new BlockHeader(4, parent.hash(), genesisBlock.header().merkleRoot(),
                        new UInt32(genesisBlock.header().timestamp().value()+i+1), genesisBlock.header().bits(), new UInt32(i+1));
                chain[i] = new Block(h, List.of());
                parent = BlockIndexFactory.createChild(parent, h); idx[i]=parent;
                blocks.save(chain[i]); indexes.save(BlockIndexStorageMapper.toStored(parent));
            }
            var result = new BlockPruner(db, 1L, 2).prune(idx[3]);
            assertEquals(2, result.blocksPruned());
            assertTrue(blocks.find(chain[0].hash()).isEmpty());
            assertTrue(blocks.find(chain[1].hash()).isEmpty());
            assertTrue(blocks.find(chain[2].hash()).isPresent());
            assertTrue(blocks.find(chain[3].hash()).isPresent());
            assertTrue(indexes.find(chain[0].hash()).isPresent());
            assertEquals(2, new RocksDbPruneStateStore(db).highestPrunedHeight().orElseThrow());
        }
    }
    @Test void manualModePrunesRequestedHistoryButKeepsSafetyWindow() {
        var params = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(temp.resolve("manual-db"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesisBlock = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesisBlock.header());
            blocks.save(genesisBlock); indexes.save(BlockIndexStorageMapper.toStored(parent));
            Block[] chain = new Block[5];
            BlockIndex[] idx = new BlockIndex[5];
            for (int i = 0; i < 5; i++) {
                var h = new BlockHeader(4, parent.hash(), genesisBlock.header().merkleRoot(),
                        new UInt32(genesisBlock.header().timestamp().value() + i + 1),
                        genesisBlock.header().bits(), new UInt32(i + 1));
                chain[i] = new Block(h, List.of());
                parent = BlockIndexFactory.createChild(parent, h); idx[i] = parent;
                blocks.save(chain[i]); indexes.save(BlockIndexStorageMapper.toStored(parent));
            }
            var pruner = new BlockPruner(db, BlockPruner.MANUAL_ONLY, 2);
            assertTrue(pruner.enabled());
            assertFalse(pruner.automatic());
            var result = pruner.pruneToHeight(idx[4], 99);
            assertEquals(3, result.highestPrunedHeight());
            assertTrue(blocks.find(chain[0].hash()).isEmpty());
            assertTrue(blocks.find(chain[1].hash()).isEmpty());
            assertTrue(blocks.find(chain[2].hash()).isEmpty());
            assertTrue(blocks.find(chain[3].hash()).isPresent());
            assertTrue(blocks.find(chain[4].hash()).isPresent());
        }
    }

}
