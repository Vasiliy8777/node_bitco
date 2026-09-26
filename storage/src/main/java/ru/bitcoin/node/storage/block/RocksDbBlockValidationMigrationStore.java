package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Durable cursor for the one-time migration of pre-status block databases. */
public final class RocksDbBlockValidationMigrationStore {
    private static final byte[] KEY = RocksDbNamespaces.singletonKey(
            RocksDbNamespaces.BLOCK_VALIDATION_MIGRATION);
    private static final int HASH_SIZE = 32;
    private static final int ACTIVE = 1;
    private static final int UNDO = 2;
    private static final int COMPLETE = 3;

    private final RocksDbDatabase database;

    public RocksDbBlockValidationMigrationStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public State load() {
        byte[] value = database.get(KEY);
        if (value == null) return State.notStarted();
        int phase = Byte.toUnsignedInt(value[0]);
        if (phase == COMPLETE && value.length == 1) return State.complete();
        if (phase == ACTIVE && value.length == 1 + HASH_SIZE) {
            return State.active(new Hash256(Arrays.copyOfRange(value, 1, value.length)));
        }
        if (phase == UNDO && (value.length == 1 || value.length == 1 + HASH_SIZE)) {
            Hash256 cursor = value.length == 1 ? null
                    : new Hash256(Arrays.copyOfRange(value, 1, value.length));
            return State.undo(cursor);
        }
        throw new IllegalStateException("Invalid block-validation migration state");
    }

    public void saveActive(RocksDbWriteBatch batch, Hash256 nextActiveHash) {
        Objects.requireNonNull(nextActiveHash, "nextActiveHash");
        batch.put(KEY, encode(ACTIVE, nextActiveHash));
    }

    public void saveUndo(RocksDbWriteBatch batch, Hash256 lastAvailabilityHash) {
        batch.put(KEY, lastAvailabilityHash == null
                ? new byte[]{(byte) UNDO}
                : encode(UNDO, lastAvailabilityHash));
    }

    public void markComplete(RocksDbWriteBatch batch) {
        batch.put(KEY, new byte[]{(byte) COMPLETE});
    }

    public void clear(RocksDbWriteBatch batch) {
        batch.delete(KEY);
    }

    private static byte[] encode(int phase, Hash256 hash) {
        byte[] raw = hash.bytes();
        if (raw.length != HASH_SIZE) throw new IllegalStateException("Invalid block hash length");
        byte[] value = new byte[1 + HASH_SIZE];
        value[0] = (byte) phase;
        System.arraycopy(raw, 0, value, 1, HASH_SIZE);
        return value;
    }

    public record State(Phase phase, Optional<Hash256> cursor) {
        static State notStarted() { return new State(Phase.NOT_STARTED, Optional.empty()); }
        static State active(Hash256 hash) { return new State(Phase.ACTIVE, Optional.of(hash)); }
        static State undo(Hash256 hash) { return new State(Phase.UNDO, Optional.ofNullable(hash)); }
        static State complete() { return new State(Phase.COMPLETE, Optional.empty()); }
    }

    public enum Phase { NOT_STARTED, ACTIVE, UNDO, COMPLETE }
}
