package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbAssumeUtxoBackgroundStoreTest {
    @TempDir
    Path dir;

    @Test void transactionProgressSurvivesRestartAndStatusChange() throws Exception {
        var hash = Hash256.fromDisplayHex("03".repeat(32));
        try (var db = new RocksDbDatabase(dir)) {
            var store = new RocksDbAssumeUtxoBackgroundStore(db);
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                store.save(batch, new RocksDbAssumeUtxoBackgroundStore.State(
                        RocksDbAssumeUtxoBackgroundStore.Status.RUNNING, hash, 17, 31));
                db.write(batch);
            }
        }
        try (var db = new RocksDbDatabase(dir)) {
            var store = new RocksDbAssumeUtxoBackgroundStore(db);
            assertEquals(31, store.load().orElseThrow().chainTxCount());
            store.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, hash, 17);
            assertEquals(31, store.load().orElseThrow().chainTxCount());
        }
    }

    @Test void readsLegacyProgressWithoutInventingATransactionCount() {
        try (var db = new RocksDbDatabase(dir)) {
            var bytes = java.nio.ByteBuffer.allocate(42).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.put((byte) 1).put((byte) 0).put(new byte[32]).putLong(100);
            db.put(new byte[]{ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.ASSUMEUTXO_BACKGROUND_STATE, 1}, bytes.array());
            var state = new RocksDbAssumeUtxoBackgroundStore(db).load().orElseThrow();
            assertEquals(100, state.tipHeight()); assertEquals(0, state.chainTxCount());
        }
    }

    @Test
    void persistsProgressAndCompletionAcrossRestart() throws Exception {
        Hash256 h1 = Hash256.fromDisplayHex("01".repeat(32));
        Hash256 h2 = Hash256.fromDisplayHex("02".repeat(32));
        try (var db = new RocksDbDatabase(dir)) {
            var store = new RocksDbAssumeUtxoBackgroundStore(db);
            store.initialize(h1, 100);
            assertEquals(RocksDbAssumeUtxoBackgroundStore.Status.RUNNING, store.load().orElseThrow().status());
            store.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, h2, 110);
        }
        try (var db = new RocksDbDatabase(dir)) {
            var state = new RocksDbAssumeUtxoBackgroundStore(db).load().orElseThrow();
            assertEquals(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, state.status());
            assertEquals(h2, state.tipHash());
            assertEquals(110, state.tipHeight());
        }
    }
}
