package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;

/**
 * Crash-safe import into an isolated namespace. Never mutates the active UTXO set.
 */
public final class UtxoSnapshotStager {
    private static final byte[] STATE_KEY = {RocksDbNamespaces.SNAPSHOT_IMPORT_STATE, 1};
    private final RocksDbDatabase db;
    private final RocksDbSnapshotStagingStore store;

    public UtxoSnapshotStager(RocksDbDatabase db) {
        this.db = Objects.requireNonNull(db);
        this.store = new RocksDbSnapshotStagingStore(db);
    }

    public record Result(Hash256 baseHash, long coinsLoaded) {
    }

    public Result stage(Path path, long networkMagic, long baseHeight) throws IOException {
        Objects.requireNonNull(path);
        clear();
        db.put(STATE_KEY, new byte[]{1}); // IMPORTING marker; restart recovery can detect incomplete state.
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            var result = UtxoSnapshotReader.read(in, networkMagic, baseHeight, store::save);
            if (store.count() != result.coinsRead()) throw new IOException("Snapshot contains duplicate outpoints");
            db.put(STATE_KEY, completeState(result.metadata().baseBlockHash(), result.coinsRead()));
            return new Result(result.metadata().baseBlockHash(), result.coinsRead());
        } catch (IOException | RuntimeException e) {
            clear();
            throw e;
        }
    }

    public boolean incompleteImportPresent() {
        byte[] v = db.get(STATE_KEY);
        return v != null && v.length == 1 && v[0] == 1;
    }

    public boolean stagedSnapshotPresent() {
        byte[] v = db.get(STATE_KEY);
        return v != null && v.length == 41 && v[0] == 2;
    }

    public void recoverIncomplete() {
        if (incompleteImportPresent()) clear();
    }

    public void clear() {
        store.clear();
        db.delete(STATE_KEY);
    }

    public long stagedCoinCount() {
        return store.count();
    }

    private static byte[] completeState(Hash256 hash, long count) {
        byte[] v = new byte[41];
        v[0] = 2;
        System.arraycopy(hash.bytes(), 0, v, 1, 32);
        for (int i = 0; i < 8; i++) v[33 + i] = (byte) (count >>> (8 * i));
        return v;
    }
}
