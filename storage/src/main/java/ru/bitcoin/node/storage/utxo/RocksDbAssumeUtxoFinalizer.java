package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.*;

/**
 * Restart-only AssumeUTXO finalization/recovery. It runs before chainstate objects are opened.
 * VALIDATED promotes the snapshot UTXO namespace to the canonical UTXO namespace.
 * INVALID reverts the persisted active tip to the fully validated historical tip.
 */
public final class RocksDbAssumeUtxoFinalizer {
    private static final byte[] STATE_KEY = {RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE, 1};
    private static final byte COPYING = 1;
    private static final int COPY_BATCH = 10_000;

    private final RocksDbDatabase db;
    private final RocksDbSnapshotChainStateStore snapshot;
    private final RocksDbAssumeUtxoBackgroundStore background;
    private final RocksDbChainStateStore tips;

    public RocksDbAssumeUtxoFinalizer(RocksDbDatabase db) {
        this.db = java.util.Objects.requireNonNull(db);
        this.snapshot = new RocksDbSnapshotChainStateStore(db);
        this.background = new RocksDbAssumeUtxoBackgroundStore(db);
        this.tips = new RocksDbChainStateStore(db);
    }

    /** Returns true when startup metadata/storage was changed. */
    public boolean finalizeOnStartup() {
        var snap = snapshot.load().orElse(null);
        var bg = background.load().orElse(null);
        if (snap == null) {
            boolean changed = clearOrphanState();
            new UtxoSnapshotStager(db).recoverIncomplete();
            return changed;
        }
        if (bg == null || bg.status() == RocksDbAssumeUtxoBackgroundStore.Status.RUNNING) return false;
        if (bg.status() == RocksDbAssumeUtxoBackgroundStore.Status.INVALID) {
            revertInvalid(snap, bg);
            return true;
        }
        if (bg.tipHeight() != snap.snapshotBaseHeight() || !bg.tipHash().equals(snap.snapshotBaseHash()))
            throw new IllegalStateException("Validated AssumeUTXO background tip does not match snapshot base");
        promoteValidated(snap);
        return true;
    }

    private void promoteValidated(RocksDbSnapshotChainStateStore.State snap) {
        // First transaction is the crash boundary: after it, canonical UTXO is known to be empty.
        if (db.get(STATE_KEY) == null) {
            try (var batch = new RocksDbWriteBatch()) {
                batch.deletePrefix(RocksDbNamespaces.UTXO);
                batch.put(STATE_KEY, new byte[]{COPYING});
                db.write(batch);
            }
        }
        copySnapshotToCanonical();
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(RocksDbNamespaces.SNAPSHOT_UTXO_STAGING);
            snapshot.clear(batch);
            background.clear(batch);
            batch.deletePrefix(RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE);
            // Do not rewrite CHAIN_STATE here. The snapshot chainstate may have advanced well
            // beyond the AssumeUTXO base while historical validation was running. The staging
            // namespace being promoted already represents that persisted active tip.
            db.write(batch);
        }
    }

    private void copySnapshotToCanonical() {
        final RocksDbWriteBatch[] batch = {new RocksDbWriteBatch()};
        final int[] count = {0};
        try {
            db.forEachEntryByPrefix(RocksDbNamespaces.SNAPSHOT_UTXO_STAGING, (key, value) -> {
                byte[] canonical = key.clone();
                canonical[0] = RocksDbNamespaces.UTXO;
                batch[0].put(canonical, value);
                if (++count[0] >= COPY_BATCH) {
                    db.write(batch[0]);
                    batch[0].close();
                    batch[0] = new RocksDbWriteBatch();
                    count[0] = 0;
                }
            });
            if (count[0] > 0) db.write(batch[0]);
        } finally {
            batch[0].close();
        }
    }

    private void revertInvalid(RocksDbSnapshotChainStateStore.State snap, RocksDbAssumeUtxoBackgroundStore.State bg) {
        // Keep the invalid snapshot coins on disk for diagnostics, but remove the activation marker
        // so no runtime path can select them again. Historical UTXO 0x03 becomes active.
        try (var batch = new RocksDbWriteBatch()) {
            snapshot.clear(batch);
            background.clear(batch);
            batch.deletePrefix(RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE);
            tips.saveActiveTipHash(batch, bg.tipHash());
            db.write(batch);
        }
    }

    private boolean clearOrphanState() {
        boolean finalization = db.get(STATE_KEY) != null;
        boolean backgroundState = background.load().isPresent();
        if (!finalization && !backgroundState) return false;
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE);
            background.clear(batch);
            db.write(batch);
        }
        return true;
    }
}
