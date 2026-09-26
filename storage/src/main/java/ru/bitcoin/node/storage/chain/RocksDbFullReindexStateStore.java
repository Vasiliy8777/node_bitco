package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Durable state used to resume and verify a full block-index rebuild after interruption. */
public final class RocksDbFullReindexStateStore {
    private static final byte[] IN_PROGRESS_KEY = {0x0C, 0x01};
    private static final byte[] LEGACY_VALUE = {0x01};
    private static final byte MANIFEST_VERSION = 0x02;
    private static final int MANIFEST_SIZE = 1 + Hash256.LENGTH + Long.BYTES + Hash256.LENGTH + Long.BYTES;
    private final RocksDbDatabase database;

    public RocksDbFullReindexStateStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public boolean isInProgress() {
        return database.get(IN_PROGRESS_KEY) != null;
    }

    /**
     * Returns the recovery manifest when this rebuild was started by the manifest-aware implementation.
     * A legacy one-byte marker intentionally returns empty so an interrupted older rebuild remains resumable.
     */
    public Optional<Manifest> manifest() {
        byte[] value = database.get(IN_PROGRESS_KEY);
        if (value == null || Arrays.equals(value, LEGACY_VALUE)) return Optional.empty();
        if (value.length != MANIFEST_SIZE || value[0] != MANIFEST_VERSION) {
            throw new IllegalStateException("Unsupported full-reindex marker value");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        buffer.get();
        byte[] activeHash = new byte[Hash256.LENGTH];
        buffer.get(activeHash);
        long activeHeight = buffer.getLong();
        byte[] bestHeaderHash = new byte[Hash256.LENGTH];
        buffer.get(bestHeaderHash);
        long bestHeaderHeight = buffer.getLong();
        if (activeHeight < 0 || bestHeaderHeight < 0) {
            throw new IllegalStateException("Invalid full-reindex manifest height");
        }
        return Optional.of(new Manifest(new Hash256(activeHash), activeHeight,
                new Hash256(bestHeaderHash), bestHeaderHeight));
    }

    /** Legacy-compatible marker, retained for callers/tests and old interrupted rebuilds. */
    public void markInProgress(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.put(IN_PROGRESS_KEY, LEGACY_VALUE);
    }

    public void markInProgress(RocksDbWriteBatch batch, Manifest manifest) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(manifest, "manifest");
        ByteBuffer buffer = ByteBuffer.allocate(MANIFEST_SIZE);
        buffer.put(MANIFEST_VERSION);
        buffer.put(manifest.expectedActiveTipHash().bytes());
        buffer.putLong(manifest.expectedActiveTipHeight());
        buffer.put(manifest.expectedBestHeaderHash().bytes());
        buffer.putLong(manifest.expectedBestHeaderHeight());
        batch.put(IN_PROGRESS_KEY, buffer.array());
    }

    public void clear(RocksDbWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        batch.delete(IN_PROGRESS_KEY);
    }

    public record Manifest(Hash256 expectedActiveTipHash, long expectedActiveTipHeight,
                           Hash256 expectedBestHeaderHash, long expectedBestHeaderHeight) {
        public Manifest {
            Objects.requireNonNull(expectedActiveTipHash, "expectedActiveTipHash");
            Objects.requireNonNull(expectedBestHeaderHash, "expectedBestHeaderHash");
            if (expectedActiveTipHeight < 0 || expectedBestHeaderHeight < 0) {
                throw new IllegalArgumentException("reindex manifest heights must not be negative");
            }
        }
    }
}
