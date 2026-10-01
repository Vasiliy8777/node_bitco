package ru.bitcoin.node.storage.rocksdb;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.LongAdder;

public final class RocksDbDatabase
        implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final long[] namespaceVersions = new long[256];

    private boolean closed;

    private void initializeNamespaceTelemetry() {
        for (int i = 0; i < 256; i++) {
            namespaceGetCount[i] = new LongAdder();
            namespaceGetNanos[i] = new LongAdder();
        }
    }

    // Lightweight cumulative I/O telemetry. LongAdder keeps the hot read path
    // contention-free enough for IBD while snapshots remain cheap.
    private final LongAdder getCount = new LongAdder();
    private final LongAdder getNanos = new LongAdder();
    private final LongAdder[] namespaceGetCount = new LongAdder[256];
    private final LongAdder[] namespaceGetNanos = new LongAdder[256];
    private final LongAdder writeBatchCount = new LongAdder();
    private final LongAdder writeBatchNanos = new LongAdder();
    private final LongAdder syncWriteBatchCount = new LongAdder();
    private final LongAdder walSyncCount = new LongAdder();
    private final LongAdder walSyncNanos = new LongAdder();

    public record NamespaceIoStats(long[] gets, long[] getNanos) {
        public NamespaceIoStats {
            gets = gets.clone();
            getNanos = getNanos.clone();
        }

        @Override public long[] gets() { return gets.clone(); }
        @Override public long[] getNanos() { return getNanos.clone(); }

        public long gets(byte namespace) { return gets[Byte.toUnsignedInt(namespace)]; }
        public long getNanos(byte namespace) { return getNanos[Byte.toUnsignedInt(namespace)]; }

        public NamespaceIoStats minus(NamespaceIoStats before) {
            long[] deltaGets = new long[256];
            long[] deltaNanos = new long[256];
            for (int i = 0; i < 256; i++) {
                deltaGets[i] = gets[i] - before.gets[i];
                deltaNanos[i] = getNanos[i] - before.getNanos[i];
            }
            return new NamespaceIoStats(deltaGets, deltaNanos);
        }
    }

    public record IoStats(long gets, long getNanos, long writeBatches, long writeBatchNanos,
                          long syncWriteBatches, long walSyncs, long walSyncNanos) {
        public IoStats minus(IoStats before) {
            return new IoStats(gets - before.gets, getNanos - before.getNanos,
                    writeBatches - before.writeBatches, writeBatchNanos - before.writeBatchNanos,
                    syncWriteBatches - before.syncWriteBatches, walSyncs - before.walSyncs,
                    walSyncNanos - before.walSyncNanos);
        }
    }

    public NamespaceIoStats namespaceIoStats() {
        long[] counts = new long[256];
        long[] nanos = new long[256];
        for (int i = 0; i < 256; i++) {
            counts[i] = namespaceGetCount[i].sum();
            nanos[i] = namespaceGetNanos[i].sum();
        }
        return new NamespaceIoStats(counts, nanos);
    }

    public IoStats ioStats() {
        return new IoStats(getCount.sum(), getNanos.sum(), writeBatchCount.sum(),
                writeBatchNanos.sum(), syncWriteBatchCount.sum(), walSyncCount.sum(), walSyncNanos.sum());
    }

    /** Per-thread IBD durability scope. Atomic WriteBatches remain WAL-backed, but
     * intermediate writes do not force an fsync; the outermost scope performs one syncWal(). */
    private final ThreadLocal<Integer> deferredSyncDepth = ThreadLocal.withInitial(() -> 0);

    public RocksDbDatabase(
            Path databasePath
    ) {
        if (databasePath == null) {
            throw new IllegalArgumentException(
                    "databasePath must not be null"
            );
        }

        initializeNamespaceTelemetry();

        try {

            Files.createDirectories(
                    databasePath
            );

            options =
                    new Options()
                            .setCreateIfMissing(true);

            database =
                    RocksDB.open(
                            options,
                            databasePath.toString()
                    );

        } catch (IOException | RocksDBException exception) {

            throw new IllegalStateException(
                    "Failed to open RocksDB: "
                            + databasePath,
                    exception
            );
        }
    }

    public synchronized void put(
            byte[] key,
            byte[] value
    ) {
        ensureOpen();

        if (key == null) {
            throw new IllegalArgumentException(
                    "key must not be null"
            );
        }

        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        try {
            database.put(
                    key,
                    value
            );
            if (key.length > 0) namespaceVersions[Byte.toUnsignedInt(key[0])]++;
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to write RocksDB value",
                    e
            );
        }
    }

    public byte[] get(
            byte[] key
    ) {
        ensureOpen();

        if (key == null) {
            throw new IllegalArgumentException(
                    "key must not be null"
            );
        }

        long started = System.nanoTime();
        int namespace = key.length == 0 ? -1 : Byte.toUnsignedInt(key[0]);
        try {
            return database.get(
                    key
            );
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to read RocksDB value",
                    e
            );
        } finally {
            long elapsed = System.nanoTime() - started;
            getCount.increment();
            getNanos.add(elapsed);
            if (namespace >= 0) {
                namespaceGetCount[namespace].increment();
                namespaceGetNanos[namespace].add(elapsed);
            }
        }
    }

    public synchronized void delete(
            byte[] key
    ) {
        ensureOpen();

        if (key == null) {
            throw new IllegalArgumentException(
                    "key must not be null"
            );
        }

        try {
            database.delete(
                    key
            );
            if (key.length > 0) namespaceVersions[Byte.toUnsignedInt(key[0])]++;
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to delete RocksDB value",
                    e
            );
        }
    }

    /**
     * Atomically applies all operations contained in the batch.
     *
     * sync=true is intentional for persistent chain-state changes.
     * When this method returns successfully, RocksDB has requested
     * that the write be synchronously flushed to durable storage.
     */
    public void write(
            RocksDbWriteBatch batch
    ) {
        write(batch, deferredSyncDepth.get() == 0);
    }

    /**
     * Runs a sequence of individually atomic WAL-backed writes with one durability barrier.
     * Visibility and write ordering are unchanged; only per-write fsync is coalesced.
     * The WAL is synchronized even when the operation fails so already committed prefixes
     * remain restart-safe. Nested scopes collapse into the outermost barrier.
     */
    public <T> T withDeferredSync(Supplier<T> operation) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        ensureOpen();
        int depth = deferredSyncDepth.get();
        deferredSyncDepth.set(depth + 1);
        try {
            return operation.get();
        } finally {
            if (depth == 0) {
                deferredSyncDepth.remove();
                syncWal();
            } else {
                deferredSyncDepth.set(depth);
            }
        }
    }

    public synchronized void syncWal() {
        ensureOpen();
        long started = System.nanoTime();
        try {
            database.syncWal();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to synchronize RocksDB WAL", e);
        } finally {
            walSyncCount.increment();
            walSyncNanos.add(System.nanoTime() - started);
        }
    }

    /**
     * Atomically applies all operations contained in the batch.
     *
     * <p>{@code sync=true} is the default for consensus/chain-state commits.
     * Header IBD may use {@code sync=false} for intermediate records inside a
     * validated network batch and finish the batch with one synchronous write.
     * RocksDB still writes those intermediate records through the WAL; the final
     * synchronous write flushes the WAL before the network batch is acknowledged
     * as processed. This avoids one fsync per header without changing visibility
     * or validation ordering.</p>
     */
    public synchronized void write(
            RocksDbWriteBatch batch,
            boolean sync
    ) {
        ensureOpen();

        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        long started = System.nanoTime();
        try (WriteOptions writeOptions =
                     new WriteOptions()
                             .setSync(sync)) {

            database.write(
                    writeOptions,
                    batch.nativeBatch()
            );
            var prefixes = batch.changedPrefixes();
            for (int prefix = prefixes.nextSetBit(0); prefix >= 0; prefix = prefixes.nextSetBit(prefix + 1)) {
                namespaceVersions[prefix]++;
            }

        } catch (RocksDBException e) {

            throw new IllegalStateException(
                    "Failed to write RocksDB batch",
                    e
            );
        } finally {
            writeBatchCount.increment();
            writeBatchNanos.add(System.nanoTime() - started);
            if (sync) syncWriteBatchCount.increment();
        }
    }

    /** Process-local generation, published only after successful writes. */
    public synchronized long namespaceVersion(byte prefix) {
        ensureOpen();
        return namespaceVersions[Byte.toUnsignedInt(prefix)];
    }

    /** Checks logical contents, including every store sharing this database. */
    public boolean isEmpty() {
        ensureOpen();
        try (var iterator = database.newIterator()) {
            iterator.seekToFirst();
            iterator.status();
            return !iterator.isValid();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to inspect RocksDB contents", e);
        }
    }

    /** Returns copies of all values in one key namespace. */
    public List<byte[]> valuesByPrefix(byte prefix) {
        ensureOpen();
        List<byte[]> values = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                values.add(iterator.value().clone());
                iterator.next();
            }
            iterator.status();
            return List.copyOf(values);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to scan RocksDB namespace", e);
        }
    }

    /** Visits keys and values in one namespace without retaining the namespace in Java memory. */
    public void forEachEntryByPrefix(byte prefix, java.util.function.BiConsumer<byte[], byte[]> visitor) {
        ensureOpen();
        java.util.Objects.requireNonNull(visitor, "visitor");
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                visitor.accept(key.clone(), iterator.value().clone());
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB namespace entries", e);
        }
    }

    /** Visits one namespace without retaining its contents in Java memory. */
    public void forEachValueByPrefix(byte prefix, java.util.function.Consumer<byte[]> visitor) {
        ensureOpen();
        java.util.Objects.requireNonNull(visitor, "visitor");
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                visitor.accept(iterator.value());
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB namespace", e);
        }
    }

    /** Visits keys in byte order until the visitor returns false. */
    public void visitPrefixAscending(byte prefix, java.util.function.BiPredicate<byte[], byte[]> visitor) {
        ensureOpen();
        java.util.Objects.requireNonNull(visitor, "visitor");
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                if (!visitor.test(key, iterator.value())) break;
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB namespace in ascending order", e);
        }
    }

    /** Visits entries whose complete key starts with an arbitrary byte prefix. */
    public void forEachEntryByKeyPrefix(byte[] keyPrefix, java.util.function.BiConsumer<byte[], byte[]> visitor) {
        ensureOpen();
        if (keyPrefix == null || keyPrefix.length == 0) throw new IllegalArgumentException("keyPrefix must not be empty");
        java.util.Objects.requireNonNull(visitor, "visitor");
        try (var iterator = database.newIterator()) {
            iterator.seek(keyPrefix);
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (!startsWith(key, keyPrefix)) break;
                visitor.accept(key.clone(), iterator.value().clone());
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB key prefix", e);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    /**
     * Visits one namespace in byte order starting strictly after a previously visited key.
     * A null cursor starts at the first key in the namespace. This is intended for
     * restart-safe, bounded migrations that persist the last processed key.
     */
    public void visitPrefixAscendingAfter(
            byte prefix,
            byte[] exclusiveAfterKey,
            java.util.function.BiPredicate<byte[], byte[]> visitor
    ) {
        ensureOpen();
        java.util.Objects.requireNonNull(visitor, "visitor");
        if (exclusiveAfterKey != null
                && (exclusiveAfterKey.length == 0 || exclusiveAfterKey[0] != prefix)) {
            throw new IllegalArgumentException("cursor must belong to the requested namespace");
        }
        try (var iterator = database.newIterator()) {
            if (exclusiveAfterKey == null) {
                iterator.seek(new byte[]{prefix});
            } else {
                iterator.seek(exclusiveAfterKey);
                if (iterator.isValid() && java.util.Arrays.equals(iterator.key(), exclusiveAfterKey)) {
                    iterator.next();
                }
            }
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                if (!visitor.test(key.clone(), iterator.value().clone())) break;
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB namespace after cursor", e);
        }
    }

    /** Visits keys in reverse byte order until the visitor returns false. */
    public void visitPrefixDescending(byte prefix, java.util.function.BiPredicate<byte[], byte[]> visitor) {
        ensureOpen();
        java.util.Objects.requireNonNull(visitor, "visitor");
        try (var iterator = database.newIterator()) {
            if (Byte.toUnsignedInt(prefix) == 255) {
                iterator.seekToLast();
            } else {
                iterator.seekForPrev(new byte[]{(byte) (Byte.toUnsignedInt(prefix) + 1)});
                if (iterator.isValid() && java.util.Arrays.equals(iterator.key(),
                        new byte[]{(byte) (Byte.toUnsignedInt(prefix) + 1)})) iterator.prev();
            }
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                if (!visitor.test(key, iterator.value())) break;
                iterator.prev();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to visit RocksDB namespace in descending order", e);
        }
    }

    /** Returns the serialized value bytes currently stored in one namespace. */
    public long valueBytesByPrefix(byte prefix) {
        ensureOpen();
        long bytes = 0L;
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                bytes = Math.addExact(bytes, iterator.value().length);
                iterator.next();
            }
            iterator.status();
            return bytes;
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to measure RocksDB namespace", e);
        }
    }

    /** Counts one key namespace without loading its values. Caller must exclude concurrent writes. */
    public long countPrefix(byte prefix) {
        ensureOpen();
        long count = 0;
        try (var iterator = database.newIterator()) {
            iterator.seek(new byte[]{prefix});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0 || key[0] != prefix) break;
                count = Math.incrementExact(count);
                iterator.next();
            }
            iterator.status();
            return count;
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to count RocksDB namespace", e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "RocksDbDatabase is already closed"
            );
        }
    }

    @Override
    public void close() {

        if (!closed) {
            closed = true;

            database.close();
            options.close();
        }
    }
}
