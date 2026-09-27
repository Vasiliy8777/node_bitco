package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

/**
 * Restart-safe progress/status for historical validation behind an active AssumeUTXO snapshot.
 */
public final class RocksDbAssumeUtxoBackgroundStore {
    private static final byte[] KEY = {RocksDbNamespaces.ASSUMEUTXO_BACKGROUND_STATE, 1};
    private static final int SIZE = 1 + 1 + 32 + 8;
    private final RocksDbDatabase db;

    public enum Status {RUNNING, VALIDATED, INVALID}

    public record State(Status status, Hash256 tipHash, long tipHeight) {
    }

    public RocksDbAssumeUtxoBackgroundStore(RocksDbDatabase db) {
        this.db = java.util.Objects.requireNonNull(db);
    }

    public Optional<State> load() {
        byte[] v = db.get(KEY);
        if (v == null) return Optional.empty();
        if (v.length != SIZE || v[0] != 1) throw new IllegalStateException("Invalid AssumeUTXO background state");
        int ordinal = v[1] & 255;
        if (ordinal >= Status.values().length) throw new IllegalStateException("Invalid AssumeUTXO background status");
        byte[] hash = java.util.Arrays.copyOfRange(v, 2, 34);
        return Optional.of(new State(Status.values()[ordinal], new Hash256(hash), readLong(v, 34)));
    }

    public void initialize(Hash256 tip, long height) {
        if (load().isPresent()) return;
        try (var batch = new RocksDbWriteBatch()) {
            save(batch, new State(Status.RUNNING, tip, height));
            db.write(batch);
        }
    }

    public void save(RocksDbWriteBatch batch, State state) {
        byte[] v = new byte[SIZE];
        v[0] = 1;
        v[1] = (byte) state.status().ordinal();
        System.arraycopy(state.tipHash().bytes(), 0, v, 2, 32);
        writeLong(v, 34, state.tipHeight());
        batch.put(KEY, v);
    }

    public void mark(Status status, Hash256 tip, long height) {
        try (var batch = new RocksDbWriteBatch()) {
            save(batch, new State(status, tip, height));
            db.write(batch);
        }
    }

    public void clear(RocksDbWriteBatch batch) {
        batch.deletePrefix(RocksDbNamespaces.ASSUMEUTXO_BACKGROUND_STATE);
    }

    private static long readLong(byte[] v, int off) {
        long n = 0;
        for (int i = 0; i < 8; i++) n |= ((long) v[off + i] & 255L) << (8 * i);
        return n;
    }

    private static void writeLong(byte[] v, int off, long n) {
        for (int i = 0; i < 8; i++) v[off + i] = (byte) (n >>> (8 * i));
    }
}
