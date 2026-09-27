package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class UtxoSnapshotVerifierTest {
    @Test
    void rejectsWrongCommittedUtxoSetAndClearsStaging() throws Exception {
        var params = NetworkParametersRegistry.regtest();
        var trusted = params.assumeUtxoData().stream().filter(a -> a.height() == 110L).findFirst().orElseThrow();
        var dir = Files.createTempDirectory("assumeutxo-verify");
        try (var db = new RocksDbDatabase(dir.resolve("db"))) {
            var active = new RocksDbUtxoStore(db);
            byte[] txid = new byte[32];
            txid[0] = 7;
            active.save(new OutPoint(new Hash256(txid), new UInt32(0)), new StoredUtxo(50_000L, new byte[]{0x51}, 100L, false));
            var snapshot = dir.resolve("snapshot.dat");
            UtxoSnapshotWriter.write(db, params.magic(), trusted.blockHash(), trusted.height(), snapshot);
            var verifier = new UtxoSnapshotVerifier(db, params);
            var error = assertThrows(java.io.IOException.class, () -> verifier.stageAndVerify(snapshot, trusted));
            assertTrue(error.getMessage().contains("hash_serialized_3"));
            assertEquals(0L, new RocksDbSnapshotStagingStore(db).count());
            assertEquals(1L, active.count());
        }
    }
}
