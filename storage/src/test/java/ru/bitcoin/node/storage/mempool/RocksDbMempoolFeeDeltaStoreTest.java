package ru.bitcoin.node.storage.mempool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocksDbMempoolFeeDeltaStoreTest {
    @TempDir Path temp;

    @Test
    void persistsDeltasForTransactionsNotInMempoolAndReplacesAtomically() {
        Hash256 a = Hash256.fromDisplayHex("11".repeat(32));
        Hash256 b = Hash256.fromDisplayHex("22".repeat(32));
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var store = new RocksDbMempoolFeeDeltaStore(db);
            store.replace(Map.of(a, 500L, b, -200L));
            assertEquals(Map.of(a, 500L, b, -200L), store.load());
            store.replace(Map.of(a, 700L));
            assertEquals(Map.of(a, 700L), store.load());
        }
    }
}
