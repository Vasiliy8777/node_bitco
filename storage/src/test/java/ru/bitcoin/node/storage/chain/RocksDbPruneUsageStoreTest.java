package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbPruneUsageStoreTest {
    @TempDir Path temp;

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
}
