package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class UtxoSnapshotStagerTest {
    @Test
    void stagesWithoutTouchingActiveUtxosAndClearsBadImport() throws Exception {
        Path d = Files.createTempDirectory("stage");
        try (var db = new RocksDbDatabase(d.resolve("db"))) {
            var active = new RocksDbUtxoStore(db);
            byte[] a = new byte[32];
            a[0] = 1;
            active.save(new OutPoint(new Hash256(a), new UInt32(0)), new StoredUtxo(1, new byte[]{0x51}, 1, false));
            Path f = d.resolve("s.dat");
            UtxoSnapshotWriter.write(db, 0xDAB5BFFAL, new Hash256(new byte[32]), 1, f);
            var stager = new UtxoSnapshotStager(db);
            var r = stager.stage(f, 0xDAB5BFFAL, 1);
            assertEquals(1, r.coinsLoaded());
            assertEquals(1, active.count());
            assertTrue(stager.stagedSnapshotPresent());
            Files.write(f, new byte[]{1, 2, 3}, StandardOpenOption.TRUNCATE_EXISTING);
            assertThrows(java.io.IOException.class, () -> stager.stage(f, 0xDAB5BFFAL, 1));
            assertEquals(0, stager.stagedCoinCount());
            assertFalse(stager.stagedSnapshotPresent());
            assertEquals(1, active.count());
        }
    }
}
