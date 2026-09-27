package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UtxoSnapshotReaderTest {
    @Test
    void readsWriterOutputStrictly() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("snap-reader");
        try (var db = new ru.bitcoin.node.storage.rocksdb.RocksDbDatabase(dir.resolve("db"))) {
            var store = new RocksDbUtxoStore(db);
            byte[] h = new byte[32];
            h[0] = 7;
            store.save(new ru.bitcoin.node.protocol.transaction.OutPoint(new Hash256(h), new ru.bitcoin.node.common.types.UInt32(2)), new StoredUtxo(42, new byte[]{0x51}, 5, false));
            java.nio.file.Path f = dir.resolve("u.dat");
            UtxoSnapshotWriter.write(db, 0xDAB5BFFAL, new Hash256(new byte[32]), 5, f);
            List<StoredUtxo> got = new ArrayList<>();
            try (var in = java.nio.file.Files.newInputStream(f)) {
                var r = UtxoSnapshotReader.read(in, 0xDAB5BFFAL, 5, (o, c) -> got.add(c));
                assertEquals(1, r.coinsRead());
                assertEquals(42, got.getFirst().amount());
            }
        }
    }

    @Test
    void rejectsTrailingBytes() throws Exception {
        byte[] meta = metadata(0);
        byte[] all = Arrays.copyOf(meta, meta.length + 1);
        assertThrows(IOException.class, () -> UtxoSnapshotReader.read(new ByteArrayInputStream(all), 0xDAB5BFFAL, 0, (o, c) -> {
        }));
    }

    private static byte[] metadata(long count) throws Exception {
        var o = new ByteArrayOutputStream();
        o.write(UtxoSnapshotWriter.MAGIC);
        o.write(2);
        o.write(0);
        for (int i = 0; i < 4; i++) o.write((int) (0xDAB5BFFAL >>> (8 * i)));
        o.write(new byte[32]);
        for (int i = 0; i < 8; i++) o.write((int) (count >>> (8 * i)));
        return o.toByteArray();
    }
}
