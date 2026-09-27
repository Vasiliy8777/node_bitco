package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbSnapshotChainStateStoreTest {
    @TempDir Path dir;

    @Test
    void activationAtomicallyPreservesNormalTipAndMovesPersistedActiveTip() {
        try (var db = new RocksDbDatabase(dir.resolve("db"))) {
            var tips = new RocksDbChainStateStore(db);
            Hash256 normal = hash(1), snapshot = hash(2);
            tips.saveActiveTipHash(normal);
            var store = new RocksDbSnapshotChainStateStore(db);
            store.activate(normal, 50, snapshot, 110);
            var state = store.load().orElseThrow();
            assertEquals(normal, state.normalTipHash());
            assertEquals(50, state.normalTipHeight());
            assertEquals(snapshot, state.snapshotBaseHash());
            assertEquals(110, state.snapshotBaseHeight());
            assertEquals(snapshot, tips.loadActiveTipHash().orElseThrow());
            assertThrows(IllegalStateException.class, () -> store.activate(snapshot, 110, hash(3), 200));
        }
    }

    @Test
    void markerSurvivesRestart() {
        Path path = dir.resolve("restart");
        Hash256 normal = hash(4), snapshot = hash(5);
        try (var db = new RocksDbDatabase(path)) {
            new RocksDbChainStateStore(db).saveActiveTipHash(normal);
            new RocksDbSnapshotChainStateStore(db).activate(normal, 10, snapshot, 20);
        }
        try (var db = new RocksDbDatabase(path)) {
            var state = new RocksDbSnapshotChainStateStore(db).load().orElseThrow();
            assertEquals(snapshot, state.snapshotBaseHash());
            assertEquals(normal, state.normalTipHash());
        }
    }

    private static Hash256 hash(int n) {
        byte[] b = new byte[32]; b[0] = (byte)n; return new Hash256(b);
    }
}
