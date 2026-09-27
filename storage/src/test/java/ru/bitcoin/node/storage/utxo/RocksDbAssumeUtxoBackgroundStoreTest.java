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
