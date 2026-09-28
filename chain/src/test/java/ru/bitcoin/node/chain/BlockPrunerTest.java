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
            var availability = new RocksDbBlockAvailabilityStore(db);
            assertFalse(availability.hasData(chain[0].hash()));
            assertFalse(availability.hasUndo(chain[0].hash()));
            assertTrue(availability.hasData(chain[2].hash()));
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

    @Test void respectsNetworkPruneAfterHeightForAutomaticAndManualPruning() {
        var params = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(temp.resolve("prune-after-height-db"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesisBlock = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesisBlock.header());
            blocks.save(genesisBlock);
            indexes.save(BlockIndexStorageMapper.toStored(parent));

            Block[] chain = new Block[5];
            BlockIndex[] idx = new BlockIndex[5];
            for (int i = 0; i < 5; i++) {
                var h = new BlockHeader(4, parent.hash(), genesisBlock.header().merkleRoot(),
                        new UInt32(genesisBlock.header().timestamp().value() + i + 1),
                        genesisBlock.header().bits(), new UInt32(i + 1));
                chain[i] = new Block(h, List.of());
                parent = BlockIndexFactory.createChild(parent, h);
                idx[i] = parent;
                blocks.save(chain[i]);
                indexes.save(BlockIndexStorageMapper.toStored(parent));
            }

            // Test override: network prune threshold is height 5, safety window is 2 blocks.
            var automatic = new BlockPruner(db, 1L, 2, 5L);
            var beforeThreshold = automatic.prune(idx[3]); // active height 4
            assertEquals(0L, beforeThreshold.blocksPruned());
            assertTrue(blocks.find(chain[0].hash()).isPresent());

            var atThreshold = automatic.prune(idx[4]); // active height 5
            assertTrue(atThreshold.blocksPruned() > 0);
            assertTrue(blocks.find(chain[0].hash()).isEmpty());
        }

        try (var db = new RocksDbDatabase(temp.resolve("manual-prune-after-height-db"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesisBlock = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesisBlock.header());
            blocks.save(genesisBlock);
            indexes.save(BlockIndexStorageMapper.toStored(parent));
            BlockIndex[] idx = new BlockIndex[4];
            for (int i = 0; i < 4; i++) {
                var h = new BlockHeader(4, parent.hash(), genesisBlock.header().merkleRoot(),
                        new UInt32(genesisBlock.header().timestamp().value() + i + 1),
                        genesisBlock.header().bits(), new UInt32(i + 20));
                var block = new Block(h, List.of());
                parent = BlockIndexFactory.createChild(parent, h);
                idx[i] = parent;
                blocks.save(block);
                indexes.save(BlockIndexStorageMapper.toStored(parent));
            }
            var manual = new BlockPruner(db, BlockPruner.MANUAL_ONLY, 2, 5L);
            var error = assertThrows(IllegalStateException.class, () -> manual.pruneToHeight(idx[3], 1));
            assertEquals("Blockchain is too short for pruning", error.getMessage());
        }
    }

    @Test void assumeUtxoCeilingProtectsUnvalidatedHistoryForAutomaticAndManualPruning() {
        var params = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(temp.resolve("assumeutxo-prune-ceiling-auto"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesis = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesis.header());
            blocks.save(genesis); indexes.save(BlockIndexStorageMapper.toStored(parent));
            Block[] chain = new Block[5];
            BlockIndex[] idx = new BlockIndex[5];
            for (int i = 0; i < chain.length; i++) {
                var header = new BlockHeader(4, parent.hash(), genesis.header().merkleRoot(),
                        new UInt32(genesis.header().timestamp().value() + i + 1),
                        genesis.header().bits(), new UInt32(100 + i));
                chain[i] = new Block(header, List.of());
                parent = BlockIndexFactory.createChild(parent, header); idx[i] = parent;
                blocks.save(chain[i]); indexes.save(BlockIndexStorageMapper.toStored(parent));
            }
            var result = new BlockPruner(db, 1L, 2).prune(idx[4], 2L);
            assertEquals(2L, result.highestPrunedHeight());
            assertTrue(blocks.find(chain[0].hash()).isEmpty());
            assertTrue(blocks.find(chain[1].hash()).isEmpty());
            assertTrue(blocks.find(chain[2].hash()).isPresent(), "height 3 is above the background-validation ceiling");
        }

        try (var db = new RocksDbDatabase(temp.resolve("assumeutxo-prune-ceiling-manual"))) {
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            Block genesis = GenesisBlockFactory.create(params);
            BlockIndex parent = BlockIndexFactory.createGenesis(genesis.header());
            blocks.save(genesis); indexes.save(BlockIndexStorageMapper.toStored(parent));
            Block[] chain = new Block[5];
            BlockIndex[] idx = new BlockIndex[5];
            for (int i = 0; i < chain.length; i++) {
                var header = new BlockHeader(4, parent.hash(), genesis.header().merkleRoot(),
                        new UInt32(genesis.header().timestamp().value() + i + 1),
                        genesis.header().bits(), new UInt32(200 + i));
                chain[i] = new Block(header, List.of());
                parent = BlockIndexFactory.createChild(parent, header); idx[i] = parent;
                blocks.save(chain[i]); indexes.save(BlockIndexStorageMapper.toStored(parent));
            }
            var result = new BlockPruner(db, BlockPruner.MANUAL_ONLY, 2).pruneToHeight(idx[4], 99L, 1L);
            assertEquals(1L, result.highestPrunedHeight());
            assertTrue(blocks.find(chain[0].hash()).isEmpty());
            assertTrue(blocks.find(chain[1].hash()).isPresent(), "manual pruning must also honor the AssumeUTXO ceiling");
        }
    }

}
