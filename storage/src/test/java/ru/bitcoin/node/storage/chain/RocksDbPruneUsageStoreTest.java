package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.nio.file.Path;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbPruneUsageStoreTest {
    @TempDir Path temp;

    @Test void migratedStoreDoesNotReadSchemaMarkerForEveryBatchEntry() {
        Path path = temp.resolve("warm-usage");
        var hash = GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).hash();
        try (var db = new RocksDbDatabase(path)) {
            var usage = new RocksDbPruneUsageStore(db);
            usage.ensureMigrated();
            long before = db.ioStats().gets();
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                for (int i = 1; i <= 128; i++) {
                    usage.setBlockSize(batch, hash, i);
                    usage.setUndoSize(batch, hash, i * 2L);
                }
                db.write(batch);
            }
            assertEquals(0L, db.ioStats().gets() - before);
            assertEquals(384L, usage.usageBytes());
        }
        try (var db = new RocksDbDatabase(path)) {
            var usage = new RocksDbPruneUsageStore(db);
            assertEquals(128L, usage.blockSize(hash));
            assertEquals(256L, usage.undoSize(hash));
        }
    }

    @Test void failedSchemaValidationIsNotCached() {
        try (var db = new RocksDbDatabase(temp.resolve("bad-version"))) {
            byte[] marker = RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION);
            db.put(marker, new byte[]{2});
            var usage = new RocksDbPruneUsageStore(db);
            assertThrows(IllegalStateException.class, usage::usageBytes);
            db.put(marker, new byte[]{1});
            assertEquals(0L, usage.usageBytes());
        }
    }

    @Test void tracksBlockAndUndoPayloadWithoutReadingPayloadForSize() {
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var blocks = new RocksDbBlockStore(db);
            var undos = new RocksDbUndoStore(db);
            var usage = new RocksDbPruneUsageStore(db);
            blocks.save(block);
            long blockSize = blocks.serializedSize(block.hash());
            assertTrue(blockSize > 80);
            assertEquals(blockSize, usage.usageBytes());
            undos.save(block.hash(), new BlockUndoData(List.of()));
            long undoSize = undos.serializedSize(block.hash());
            assertTrue(undoSize > 0);
            assertEquals(blockSize + undoSize, usage.usageBytes());
            undos.delete(block.hash());
            assertEquals(blockSize, usage.usageBytes());
            blocks.delete(block.hash());
            assertEquals(0L, usage.usageBytes());
        }
    }

    @Test void clearUndoNamespaceClearsUndoSizeIndexAtomically() {
        try (var db = new RocksDbDatabase(temp.resolve("clear"))) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var blocks = new RocksDbBlockStore(db);
            var undos = new RocksDbUndoStore(db);
            blocks.save(block);
            undos.save(block.hash(), new BlockUndoData(List.of()));
            long blockSize = blocks.serializedSize(block.hash());
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                undos.clear(batch);
                db.write(batch);
            }
            assertEquals(0L, undos.serializedSize(block.hash()));
            assertEquals(blockSize, new RocksDbPruneUsageStore(db).usageBytes());
        }
    }

    @Test void legacyUsageMarkerMigratesAwayFromHeightIndexNamespace() {
        Path path = temp.resolve("legacy-marker");
        try (var db = new RocksDbDatabase(path)) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var blocks = new RocksDbBlockStore(db);
            blocks.save(block);
            long expected = blocks.serializedSize(block.hash());

            // Recreate the exact metadata layout written before the namespace fix.
            db.delete(RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION));
            db.put(RocksDbNamespaces.LEGACY_PRUNE_USAGE_VERSION_KEY, new byte[]{1});

            assertEquals(expected, new RocksDbPruneUsageStore(db).usageBytes());
            assertNull(db.get(RocksDbNamespaces.LEGACY_PRUNE_USAGE_VERSION_KEY));
            assertArrayEquals(new byte[]{1},
                    db.get(RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION)));
        }
    }

    @Test void clearingHeightIndexCannotInvalidatePruneUsageMigrationState() {
        Path path = temp.resolve("height-clear");
        try (var db = new RocksDbDatabase(path)) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var blocks = new RocksDbBlockStore(db);
            blocks.save(block);
            long expected = blocks.serializedSize(block.hash());

            var header = block.header();
            var indexes = new RocksDbBlockIndexStore(db);
            indexes.save(new StoredBlockIndex(
                    block.hash(), header, 0L, header.previousBlockHash(), BigInteger.ONE));
            indexes.visitByHeightAscending(index -> true);

            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                indexes.clear(batch);
                db.write(batch);
            }

            assertArrayEquals(new byte[]{1},
                    db.get(RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION)));
            assertEquals(expected, new RocksDbPruneUsageStore(db).usageBytes());
        }
    }

    @Test void missingLegacyMarkerAfterHeightClearRebuildsUsageFromPayloads() {
        Path path = temp.resolve("legacy-cleared");
        try (var db = new RocksDbDatabase(path)) {
            var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            var blocks = new RocksDbBlockStore(db);
            blocks.save(block);
            long expected = blocks.serializedSize(block.hash());

            db.delete(RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION));
            db.put(RocksDbNamespaces.LEGACY_PRUNE_USAGE_VERSION_KEY, new byte[]{1});

            var indexes = new RocksDbBlockIndexStore(db);
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                indexes.clear(batch);
                db.write(batch);
            }
            assertNull(db.get(RocksDbNamespaces.LEGACY_PRUNE_USAGE_VERSION_KEY));

            // No marker remains, so ensureMigrated must reconstruct the compact index.
            assertEquals(expected, new RocksDbPruneUsageStore(db).usageBytes());
            assertArrayEquals(new byte[]{1},
                    db.get(RocksDbNamespaces.singletonKey(RocksDbNamespaces.PRUNE_USAGE_VERSION)));
        }
    }

}
