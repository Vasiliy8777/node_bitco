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
    void lazilyMigratesLegacyPayloadAndThenTracksAtomicStatusChanges() {
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var hash = block.hash();
            var blocks = new RocksDbBlockStore(db);
            var undos = new RocksDbUndoStore(db);

            // Simulate a pre-availability-metadata database.
            blocks.save(block);
            undos.save(hash, new BlockUndoData(List.of()));

            var availability = new RocksDbBlockAvailabilityStore(db);
            assertTrue(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash));

            try (var batch = new RocksDbWriteBatch()) {
                blocks.delete(batch, hash);
                undos.delete(batch, hash);
                availability.clearDataAndUndo(batch, hash);
                db.write(batch);
            }
            assertFalse(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash));

            try (var batch = new RocksDbWriteBatch()) {
                blocks.save(batch, block);
                availability.markData(batch, hash);
                db.write(batch);
            }
            assertTrue(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash));
        }
    }
}
