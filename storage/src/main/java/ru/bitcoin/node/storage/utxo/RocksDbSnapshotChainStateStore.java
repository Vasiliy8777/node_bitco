package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

/** Durable activation marker for the secondary AssumeUTXO chainstate. */
public final class RocksDbSnapshotChainStateStore {
    private static final byte[] KEY = {RocksDbNamespaces.SNAPSHOT_CHAINSTATE, 1};
    private static final int SIZE = 1 + 32 + 8 + 32 + 8;
    private final RocksDbDatabase db;

    public RocksDbSnapshotChainStateStore(RocksDbDatabase db) {
        this.db = java.util.Objects.requireNonNull(db);
    }

    public record State(Hash256 snapshotBaseHash, long snapshotBaseHeight,
                        Hash256 normalTipHash, long normalTipHeight) {}

    public Optional<State> load() {
        byte[] v = db.get(KEY);
        if (v == null) return Optional.empty();
        if (v.length != SIZE || v[0] != 1) throw new IllegalStateException("Invalid snapshot chainstate marker");
        byte[] base = java.util.Arrays.copyOfRange(v, 1, 33);
        long baseHeight = readLong(v, 33);
        byte[] normal = java.util.Arrays.copyOfRange(v, 41, 73);
        long normalHeight = readLong(v, 73);
        return Optional.of(new State(new Hash256(base), baseHeight, new Hash256(normal), normalHeight));
    }

    /** Atomically makes the verified snapshot the persisted active chainstate. */
    public void activate(Hash256 expectedNormalTip, long normalHeight,
                         Hash256 snapshotBase, long snapshotHeight) {
        var tips = new RocksDbChainStateStore(db);
        Hash256 actual = tips.loadActiveTipHash().orElseThrow(() -> new IllegalStateException("Active tip is not initialized"));
        if (!actual.equals(expectedNormalTip)) throw new IllegalStateException("Active tip changed before snapshot activation");
        if (load().isPresent()) throw new IllegalStateException("A snapshot chainstate is already active");
        byte[] state = new byte[SIZE];
        state[0] = 1;
        System.arraycopy(snapshotBase.bytes(), 0, state, 1, 32);
        writeLong(state, 33, snapshotHeight);
        System.arraycopy(expectedNormalTip.bytes(), 0, state, 41, 32);
        writeLong(state, 73, normalHeight);
        try (var batch = new RocksDbWriteBatch()) {
            batch.put(KEY, state);
            tips.saveActiveTipHash(batch, snapshotBase);
            db.write(batch);
        }
    }


    public void clear(RocksDbWriteBatch batch) {
        java.util.Objects.requireNonNull(batch, "batch");
        batch.deletePrefix(RocksDbNamespaces.SNAPSHOT_CHAINSTATE);
    }

    private static long readLong(byte[] v, int off) {
        long n=0; for(int i=0;i<8;i++) n |= ((long)v[off+i]&255L) << (8*i); return n;
    }
    private static void writeLong(byte[] v, int off, long n) {
        for(int i=0;i<8;i++) v[off+i]=(byte)(n >>> (8*i));
    }
}
