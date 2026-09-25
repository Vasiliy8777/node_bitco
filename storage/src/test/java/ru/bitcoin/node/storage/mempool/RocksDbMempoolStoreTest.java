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
            store.apply(List.of(), List.of(tx1, tx2));
            assertEquals(2, store.load().size());
            store.apply(List.of(tx1, tx2), List.of(tx2));
        }
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var loaded = new RocksDbMempoolStore(db).load();
            assertEquals(1, loaded.size());
            assertEquals(tx2.wtxId(), loaded.getFirst().wtxId());
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
