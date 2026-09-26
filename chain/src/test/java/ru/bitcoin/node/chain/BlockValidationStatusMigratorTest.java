package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockValidationMigrationStore;
import ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BlockValidationStatusMigratorTest {
    @TempDir Path directory;

    @Test
    void backfillsActiveAncestryAndFormerConnectedForkButNotHeadersOnlyFork() {
        try (var db = new RocksDbDatabase(directory)) {
            Fixture f = fixture(db);
            var status = new RocksDbBlockValidationStatusStore(db);
            assertFalse(status.isScriptsValid(f.activeTip.hash()));
            assertFalse(status.isScriptsValid(f.formerFork.hash()));
            assertFalse(status.isScriptsValid(f.headersOnlyFork.hash()));

            var result = new BlockValidationStatusMigrator(db, 2).migrate();
            assertTrue(result.complete());
            assertTrue(result.batchesCommitted() >= 3); // active chunks + availability chunks/completion
            assertTrue(status.isScriptsValid(f.genesis.hash()));
            assertTrue(status.isScriptsValid(f.active1.hash()));
            assertTrue(status.isScriptsValid(f.activeTip.hash()));
            assertTrue(status.isScriptsValid(f.formerFork.hash()));
            assertFalse(status.isScriptsValid(f.headersOnlyFork.hash()));
            assertEquals(RocksDbBlockValidationMigrationStore.Phase.COMPLETE,
                    new RocksDbBlockValidationMigrationStore(db).load().phase());

            var second = new BlockValidationStatusMigrator(db, 2).migrate();
            assertEquals(0, second.statusesMarked());
            assertEquals(0, second.batchesCommitted());
        }
    }

    @Test
    void resumesFromDurableActiveCursor() {
        try (var db = new RocksDbDatabase(directory)) {
            Fixture f = fixture(db);
            var status = new RocksDbBlockValidationStatusStore(db);
            var migration = new RocksDbBlockValidationMigrationStore(db);
            // Simulate a committed first migration chunk: tip was marked and cursor advanced.
            try (var batch = new RocksDbWriteBatch()) {
                status.markScriptsValid(batch, f.activeTip.hash());
                migration.saveActive(batch, f.active1.hash());
                db.write(batch);
            }

            new BlockValidationStatusMigrator(db, 1).migrate();
            assertTrue(status.isScriptsValid(f.activeTip.hash()));
            assertTrue(status.isScriptsValid(f.active1.hash()));
            assertTrue(status.isScriptsValid(f.genesis.hash()));
            assertTrue(status.isScriptsValid(f.formerFork.hash()));
            assertEquals(RocksDbBlockValidationMigrationStore.Phase.COMPLETE, migration.load().phase());
        }
    }

    @Test
    void freshEmptyDatabaseDefersMigrationWithoutPersistingCompletion() throws Exception {
        try (var db = new RocksDbDatabase(directory.resolve("empty-db"))) {
            var migration = new RocksDbBlockValidationMigrationStore(db);

            var result = new BlockValidationStatusMigrator(db, 2).migrate();

            assertEquals(0L, result.statusesMarked());
            assertEquals(0L, result.batchesCommitted());
            assertFalse(result.complete());
            assertEquals(
                    RocksDbBlockValidationMigrationStore.Phase.NOT_STARTED,
                    migration.load().phase()
            );
        }
    }

    private static Fixture fixture(RocksDbDatabase db) {
        var params = NetworkParametersRegistry.regtest();
        var genesisBlock = GenesisBlockFactory.create(params);
        var genesis = BlockIndexFactory.createGenesis(genesisBlock.header());
        var indexes = new RocksDbBlockIndexStore(db);
        indexes.save(BlockIndexStorageMapper.toStored(genesis));

        BlockIndex active1 = child(genesis, 1, 1);
        BlockIndex activeTip = child(active1, 2, 2);
        BlockIndex formerFork = child(genesis, 3, 11);
        BlockIndex headersOnlyFork = child(genesis, 4, 12);
        indexes.save(BlockIndexStorageMapper.toStored(active1));
        indexes.save(BlockIndexStorageMapper.toStored(activeTip));
        indexes.save(BlockIndexStorageMapper.toStored(formerFork));
        indexes.save(BlockIndexStorageMapper.toStored(headersOnlyFork));

        var tips = new RocksDbChainStateStore(db);
        tips.saveActiveTipHash(activeTip.hash());
        tips.saveBestHeaderTipHash(activeTip.hash());

        var availability = new RocksDbBlockAvailabilityStore(db);
        try (var batch = new RocksDbWriteBatch()) {
            availability.markUndo(batch, formerFork.hash());
            db.write(batch);
        }
        // Materialize metadata for a headers-only fork with no HAVE_UNDO bit.
        assertFalse(availability.hasUndo(headersOnlyFork.hash()));
        return new Fixture(genesis, active1, activeTip, formerFork, headersOnlyFork);
    }

    private static BlockIndex child(BlockIndex parent, long seconds, long nonce) {
        var genesis = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        BlockHeader header = new BlockHeader(
                4,
                parent.hash(),
                genesis.header().merkleRoot(),
                new UInt32(genesis.header().timestamp().value() + seconds),
                genesis.header().bits(),
                new UInt32(nonce));
        return BlockIndexFactory.createChild(parent, header);
    }

    private record Fixture(BlockIndex genesis, BlockIndex active1, BlockIndex activeTip,
                           BlockIndex formerFork, BlockIndex headersOnlyFork) {}
}
