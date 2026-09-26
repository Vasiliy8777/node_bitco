package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockValidationMigrationStore;
import ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Restart-safe migration for databases created before persistent block validation status existed.
 *
 * <p>The current active ancestry is known to have been connected successfully. In addition,
 * HAVE_UNDO is durable evidence that a block was connected at some point, so disconnected former
 * active branches can be restored to SCRIPTS_VALID without promoting headers-only branches.</p>
 */
public final class BlockValidationStatusMigrator {
    static final int DEFAULT_BATCH_SIZE = 4096;

    private final RocksDbDatabase database;
    private final int batchSize;

    public BlockValidationStatusMigrator(RocksDbDatabase database) {
        this(database, DEFAULT_BATCH_SIZE);
    }

    BlockValidationStatusMigrator(RocksDbDatabase database, int batchSize) {
        this.database = Objects.requireNonNull(database, "database");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    public Result migrate() {
        synchronized (database) {
            var migration = new RocksDbBlockValidationMigrationStore(database);
            var status = new RocksDbBlockValidationStatusStore(database);
            var indexes = new RocksDbBlockIndexStore(database);
            var tips = new RocksDbChainStateStore(database);
            long marked = 0L;
            long batches = 0L;

            var state = migration.load();
            if (state.phase() == RocksDbBlockValidationMigrationStore.Phase.COMPLETE) {
                return new Result(0L, 0L, true);
            }

            Hash256 activeCursor;
            if (state.phase() == RocksDbBlockValidationMigrationStore.Phase.NOT_STARTED) {
                var activeTip = tips.loadActiveTipHash();
                if (activeTip.isEmpty()) {
                    /*
                     * A freshly created database has no chainstate yet. Startup
                     * configuration is allowed to construct validation services
                     * before ChainInitializer persists genesis, so this is not
                     * corruption and must not permanently complete migration.
                     *
                     * Leaving the durable state at NOT_STARTED guarantees that a
                     * later startup, after chainstate exists, performs the real
                     * backfill.
                     */
                    return new Result(0L, 0L, false);
                }
                activeCursor = activeTip.orElseThrow();
            } else if (state.phase() == RocksDbBlockValidationMigrationStore.Phase.ACTIVE) {
                activeCursor = state.cursor().orElseThrow();
            } else {
                activeCursor = null;
            }

            while (activeCursor != null) {
                Hash256 cursor = activeCursor;
                int processed = 0;
                Hash256 next = null;
                try (var batch = new RocksDbWriteBatch()) {
                    while (processed < batchSize) {
                        var storedIndex = indexes.find(cursor);
                        if (storedIndex.isEmpty()) {
                            throw new IllegalStateException(
                                    "Cannot migrate validation status: active BlockIndex is missing: "
                                            + cursor.toDisplayHex());
                        }
                        BlockIndex index = BlockIndexStorageMapper.fromStored(storedIndex.orElseThrow());
                        status.markScriptsValid(batch, index.hash());
                        processed++;
                        if (index.height() == 0) {
                            next = null;
                            migration.saveUndo(batch, null);
                            break;
                        }
                        BlockIndex parent = indexes.find(index.previousBlockHash())
                                .map(BlockIndexStorageMapper::fromStored)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Cannot migrate validation status: active ancestor is missing"));
                        if (parent.height() != index.height() - 1) {
                            throw new IllegalStateException(
                                    "Cannot migrate validation status: inconsistent active ancestry");
                        }
                        cursor = parent.hash();
                        next = cursor;
                    }
                    if (next != null) migration.saveActive(batch, next);
                    database.write(batch);
                }
                marked += processed;
                batches++;
                activeCursor = next;
            }

            state = migration.load();
            Hash256 availabilityCursor = state.phase() == RocksDbBlockValidationMigrationStore.Phase.UNDO
                    ? state.cursor().orElse(null) : null;

            while (true) {
                List<Hash256> validated = new ArrayList<>();
                Hash256[] lastVisited = {null};
                int[] visited = {0};
                byte[] afterKey = availabilityCursor == null ? null : availabilityKey(availabilityCursor);
                database.visitPrefixAscendingAfter(
                        RocksDbNamespaces.BLOCK_AVAILABILITY,
                        afterKey,
                        (key, value) -> {
                            if (key.length != 33) throw new IllegalStateException(
                                    "Invalid block availability key length");
                            if (value.length != 1) throw new IllegalStateException(
                                    "Invalid block availability metadata size");
                            Hash256 hash = new Hash256(java.util.Arrays.copyOfRange(key, 1, 33));
                            lastVisited[0] = hash;
                            visited[0]++;
                            if ((Byte.toUnsignedInt(value[0]) & RocksDbBlockAvailabilityStore.HAVE_UNDO) != 0) {
                                validated.add(hash);
                            }
                            return visited[0] < batchSize;
                        });

                if (visited[0] == 0) {
                    try (var batch = new RocksDbWriteBatch()) {
                        migration.markComplete(batch);
                        database.write(batch);
                    }
                    batches++;
                    break;
                }

                try (var batch = new RocksDbWriteBatch()) {
                    for (Hash256 hash : validated) {
                        if (indexes.find(hash).isEmpty()) {
                            throw new IllegalStateException(
                                    "Cannot migrate validation status: HAVE_UNDO block has no BlockIndex: "
                                            + hash.toDisplayHex());
                        }
                        status.markScriptsValid(batch, hash);
                    }
                    migration.saveUndo(batch, lastVisited[0]);
                    database.write(batch);
                }
                marked += validated.size();
                batches++;
                availabilityCursor = lastVisited[0];
            }
            return new Result(marked, batches, true);
        }
    }

    private static byte[] availabilityKey(Hash256 hash) {
        byte[] raw = hash.bytes();
        byte[] key = new byte[33];
        key[0] = RocksDbNamespaces.BLOCK_AVAILABILITY;
        System.arraycopy(raw, 0, key, 1, 32);
        return key;
    }

    public record Result(long statusesMarked, long batchesCommitted, boolean complete) {}
}
