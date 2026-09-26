package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockAvailabilityStoreTest {
    @TempDir Path temp;

    @Test
    void payloadStoresOwnAvailabilityFlagsAcrossDirectAndBatchWrites() {
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var hash = block.hash();
            var blocks = new RocksDbBlockStore(db);
            var undos = new RocksDbUndoStore(db);
            var availability = new RocksDbBlockAvailabilityStore(db);

            blocks.save(block);
            assertTrue(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash));

            undos.save(hash, new BlockUndoData(List.of()));
            assertTrue(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash));

            blocks.delete(hash);
            assertFalse(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash));

            undos.delete(hash);
            assertFalse(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash));

            try (var batch = new RocksDbWriteBatch()) {
                blocks.save(batch, block);
                undos.save(batch, hash, new BlockUndoData(List.of()));
                db.write(batch);
            }
            assertTrue(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash));
        }
    }

    @Test
    void batchAvailabilityUpdatesReadTheirOwnWritesAndRespectPrefixClear() {
        try (var db = new RocksDbDatabase(temp.resolve("overlay"))) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var hash = block.hash();
            var blocks = new RocksDbBlockStore(db);
            var undos = new RocksDbUndoStore(db);
            var availability = new RocksDbBlockAvailabilityStore(db);

            blocks.save(block);
            undos.save(hash, new BlockUndoData(List.of()));
            assertEquals(
                    RocksDbBlockAvailabilityStore.HAVE_DATA | RocksDbBlockAvailabilityStore.HAVE_UNDO,
                    availability.status(hash));

            try (var batch = new RocksDbWriteBatch()) {
                availability.clear(batch);
                blocks.save(batch, block);
                db.write(batch);
            }

            assertTrue(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash),
                    "Prefix clear followed by body save must not resurrect stale HAVE_UNDO");

            try (var batch = new RocksDbWriteBatch()) {
                availability.clearData(batch, hash);
                availability.markData(batch, hash);
                availability.markUndo(batch, hash);
                db.write(batch);
            }
            assertTrue(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash),
                    "Multiple metadata updates in one batch must compose instead of reading stale DB state");
        }
    }

}
