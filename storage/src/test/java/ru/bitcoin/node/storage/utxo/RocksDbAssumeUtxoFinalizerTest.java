package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.*;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbAssumeUtxoFinalizerTest {
    @TempDir
    Path dir;

    @Test
    void validatedSnapshotIsPromotedAndMarkersDisappear() {
        try (var db = new RocksDbDatabase(dir.resolve("ok"))) {
            Hash256 normal = hash(1), base = hash(2);
            var tips = new RocksDbChainStateStore(db);
            tips.saveActiveTipHash(normal);
            new RocksDbSnapshotChainStateStore(db).activate(normal, 10, base, 20);
            put(db, RocksDbNamespaces.UTXO, (byte) 7, (byte) 70);
            put(db, RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, (byte) 8, (byte) 80);
            var bg = new RocksDbAssumeUtxoBackgroundStore(db);
            bg.initialize(normal, 10);
            bg.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, base, 20);
            assertTrue(new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup());
            assertTrue(new RocksDbSnapshotChainStateStore(db).load().isEmpty());
            assertTrue(bg.load().isEmpty());
            assertEquals(base, tips.loadActiveTipHash().orElseThrow());
            assertNull(db.get(new byte[]{RocksDbNamespaces.UTXO, 7}));
            assertArrayEquals(new byte[]{80}, db.get(new byte[]{RocksDbNamespaces.UTXO, 8}));
            assertNull(db.get(new byte[]{RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, 8}));
        }
    }

    @Test
    void invalidSnapshotRevertsToHistoricalTipWithoutPromotingCoins() {
        try (var db = new RocksDbDatabase(dir.resolve("bad"))) {
            Hash256 normal = hash(3), base = hash(4), historical = hash(5);
            var tips = new RocksDbChainStateStore(db);
            tips.saveActiveTipHash(normal);
            new RocksDbSnapshotChainStateStore(db).activate(normal, 10, base, 20);
            put(db, RocksDbNamespaces.UTXO, (byte) 1, (byte) 11);
            put(db, RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, (byte) 2, (byte) 22);
            var bg = new RocksDbAssumeUtxoBackgroundStore(db);
            bg.initialize(normal, 10);
            bg.mark(RocksDbAssumeUtxoBackgroundStore.Status.INVALID, historical, 15);
            new RocksDbAssumeUtxoFinalizer(db).finalizeOnStartup();
            assertEquals(historical, tips.loadActiveTipHash().orElseThrow());
            assertTrue(new RocksDbSnapshotChainStateStore(db).load().isEmpty());
            assertArrayEquals(new byte[]{11}, db.get(new byte[]{RocksDbNamespaces.UTXO, 1}));
            assertArrayEquals(new byte[]{22}, db.get(new byte[]{RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, 2}));
        }
    }

    private static void put(RocksDbDatabase db, byte prefix, byte suffix, byte value) {
        db.put(new byte[]{prefix, suffix}, new byte[]{value});
    }

    private static Hash256 hash(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new Hash256(b);
    }
}
