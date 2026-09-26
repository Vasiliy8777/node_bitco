package ru.bitcoin.node.storage.mempool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbMempoolStoreTest {
    @TempDir
    Path temp;

    @Test
    void persistsWitnessTransactionAndAppliesMembershipDelta() {
        var tx1 = tx((byte) 1, false);
        var tx2 = tx((byte) 2, true);
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var store = new RocksDbMempoolStore(db);
            var e1 = new PersistedMempoolEntry(tx1, 1_000L);
            var e2 = new PersistedMempoolEntry(tx2, 2_000L);
            store.apply(List.of(), List.of(e1, e2));
            assertEquals(2, store.load().size());
            store.apply(List.of(e1, e2), List.of(e2));
        }
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var loaded = new RocksDbMempoolStore(db).load();
            assertEquals(1, loaded.size());
            assertEquals(tx2.wtxId(), loaded.getFirst().transaction().wtxId());
            assertEquals(2_000L, loaded.getFirst().arrivalTime());
        }
    }

    @Test
    void replacePreservesArrivalTimeMetadata() {
        var transaction = tx((byte) 3, true);
        try (var db = new RocksDbDatabase(temp.resolve("metadata"))) {
            var store = new RocksDbMempoolStore(db);
            store.replace(List.of(new PersistedMempoolEntry(transaction, 123_456L)));
            var loaded = store.load();
            assertEquals(1, loaded.size());
            assertEquals(123_456L, loaded.getFirst().arrivalTime());
            assertEquals(transaction.wtxId(), loaded.getFirst().transaction().wtxId());
        }
    }

    private static Transaction tx(byte tag, boolean witness) {
        var input = new TxIn(new OutPoint(new Hash256(fill(tag)), new UInt32(0)), new byte[]{0x51}, TxIn.FINAL_SEQUENCE,
                witness ? new Witness(List.of(new byte[]{tag})) : Witness.EMPTY);
        return new Transaction(2, List.of(input), List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
    }

    private static byte[] fill(byte b) {
        byte[] v = new byte[32];
        java.util.Arrays.fill(v, b);
        return v;
    }
}
