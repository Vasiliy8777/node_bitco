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
    private final org.rocksdb.LRUCache blockCache;
    private final org.rocksdb.BloomFilter bloomFilter;
    private final Path databasePath;
    private final long networkMagic;
    private final long[] namespaceVersions = new long[256];
    private final BlockMetadataCache metadataCache = new BlockMetadataCache();

    private boolean closed;
    // Hints only warm the native version-aware cache. Disposal excludes these reads.
    private final java.util.concurrent.locks.ReentrantReadWriteLock warmReadLifetime =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final LongAdder warmKeysCount = new LongAdder();
    private final LongAdder warmReadNanos = new LongAdder();
    private final java.util.Map<String, FlatFileRecordStore> payloadFiles = new java.util.HashMap<>();

    /** One sequential writer per payload prefix, with the database owning its lifetime. */
    public synchronized FlatFileRecordStore payloadFiles(String prefix) {
        ensureOpen();
        return payloadFiles.computeIfAbsent(prefix, key -> new FlatFileRecordStore(
                externalDataRoot().resolve("blocks"), key, networkMagic,
                FlatFileRecordStore.DEFAULT_MAX_FILE_SIZE, true));
    }

    private void flushPayloadFiles() {
        payloadFiles.values().forEach(FlatFileRecordStore::flush);
    }

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
    private final LongAdder nativeReadKeys = new LongAdder();
    private final LongAdder metadataCacheHits = new LongAdder();

    /** Actual native reads, separate from the historical logical-get telemetry. */
    public record ReadCacheStats(long nativeKeys, long metadataHits) { }

    public ReadCacheStats readCacheStats() {
        return new ReadCacheStats(nativeReadKeys.sum(), metadataCacheHits.sum());
    }
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

    // Core-style chainstate write-back cache. While enabled, consensus/index mutations
    // remain process-visible through read-your-writes and are checkpointed atomically
    // by pressure/time policy. A crash rolls back to the last complete checkpoint.
    private RocksDbWriteBatch chainstateWriteBack;
    private boolean chainstateWriteBackEnabled;
    private long chainstateWriteBackStartedNanos;
    private static final long WRITE_BACK_MAX_BYTES = 64L * 1024L * 1024L;
    private static final int WRITE_BACK_MAX_OPERATIONS = 250_000;
    private static final long WRITE_BACK_MAX_AGE_NANOS = java.time.Duration.ofSeconds(30).toNanos();

    public RocksDbDatabase(
            Path databasePath
    ) {
        this(databasePath, 0xD9B4BEF9L);
    }

    public RocksDbDatabase(
            Path databasePath,
            long networkMagic
    ) {
        this(databasePath, networkMagic, 128);
    }

    public RocksDbDatabase(Path databasePath, long networkMagic, int blockCacheMiB) {
        if (databasePath == null) {
            throw new IllegalArgumentException(
                    "databasePath must not be null"
            );
        }

        initializeNamespaceTelemetry();
        if (blockCacheMiB < 8 || blockCacheMiB > 16384)
            throw new IllegalArgumentException("blockCacheMiB must be between 8 and 16384");
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.networkMagic = networkMagic;

        // Keep hot index/coins SST blocks in RAM rather than relying on RocksDB's
        // small implicit cache. Bloom filters avoid disk reads for absent coins.
        blockCache = new org.rocksdb.LRUCache(blockCacheMiB * 1024L * 1024L);
        bloomFilter = new org.rocksdb.BloomFilter(10, false);
        options = new Options().setCreateIfMissing(true).setTableFormatConfig(
                new org.rocksdb.BlockBasedTableConfig().setBlockCache(blockCache)
                        .setCacheIndexAndFilterBlocks(true).setFilterPolicy(bloomFilter));

        try {

            Files.createDirectories(
                    this.databasePath
            );

            database =
                    RocksDB.open(
                            options,
                            this.databasePath.toString()
                    );

        } catch (IOException | RocksDBException exception) {
            options.close();
            bloomFilter.close();
            blockCache.close();

            throw new IllegalStateException(
                    "Failed to open RocksDB: "
                            + databasePath,
                    exception
            );
        }
    }


    /** Physical RocksDB directory. Large immutable payload stores derive their sibling data directory from it. */
    public Path databasePath() {
        return databasePath;
    }

    public long networkMagic() { return networkMagic; }

    /**
     * Root for non-RocksDB node data. Production opens RocksDB as <network>/chainstate,
     * so block files live in <network>/blocks. Tests using arbitrary DB names get an
     * isolated sibling directory instead of writing inside an open RocksDB directory.
     */
    public Path externalDataRoot() {
        Path name = databasePath.getFileName();
        if (name != null && "chainstate".equalsIgnoreCase(name.toString())) {
            Path parent = databasePath.getParent();
            return parent == null ? databasePath.resolveSibling("data") : parent;
        }
        String suffix = name == null ? "node-data" : name + ".data";
        return databasePath.resolveSibling(suffix);
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
            if (chainstateWriteBackEnabled || chainstateWriteBack != null) {
                ensureWriteBack().put(key, value);
                metadataCache.invalidate(key);
                if (key.length > 0) namespaceVersions[Byte.toUnsignedInt(key[0])]++;
                flushChainstateIfNeeded();
                return;
            }
            database.put(key, value);
            metadataCache.invalidate(key);
            metadataCache.remember(key, value);
            if (key.length > 0) namespaceVersions[Byte.toUnsignedInt(key[0])]++;
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to write RocksDB value",
                    e
            );
        }
    }

    public synchronized byte[] get(
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
            if (chainstateWriteBack != null) {
                var pending = chainstateWriteBack.pendingValue(key);
                if (pending.touched()) return pending.value();
            }
            if (metadataCache.contains(key)) {
                metadataCacheHits.increment();
                return metadataCache.get(key);
            }
            nativeReadKeys.increment();
            byte[] value = database.get(key);
            metadataCache.remember(key, value);
            return value;
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

    /**
     * Native RocksDB MultiGet. Telemetry keeps the historical meaning of
     * getCount/namespaceGetCount as keys resolved rather than JNI calls made,
     * so IBD diagnostics remain comparable before and after batching.
     */
    public List<byte[]> getAll(List<byte[]> keys) {
        if (keys == null) throw new IllegalArgumentException("keys must not be null");
        for (byte[] key : keys) {
            if (key == null) throw new IllegalArgumentException("keys must not contain null");
        }
        long started = System.nanoTime();
        List<byte[]> result = new ArrayList<>(java.util.Collections.nCopies(keys.size(), null));
        List<byte[]> unresolved = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        org.rocksdb.Snapshot snapshot = null;
        long[] versions;
        synchronized (this) {
            ensureOpen();
            if (keys.isEmpty()) return List.of();
            versions = namespaceVersions.clone();
            for (int i = 0; i < keys.size(); i++) {
                byte[] key = keys.get(i);
                var pending = chainstateWriteBack == null ? null : chainstateWriteBack.pendingValue(key);
                if (pending != null && pending.touched()) result.set(i, pending.value());
                else if (metadataCache.contains(key)) {
                    metadataCacheHits.increment();
                    result.set(i, metadataCache.get(key));
                } else {
                    unresolved.add(key);
                    positions.add(i);
                }
            }
            if (!unresolved.isEmpty()) {
                // Capture native and write-back values at the same linearization
                // point. Disposal must wait, but other database operations need
                // not wait for potentially seconds of disk reads.
                warmReadLifetime.readLock().lock();
                try { snapshot = database.getSnapshot(); }
                catch (RuntimeException | Error failure) {
                    warmReadLifetime.readLock().unlock();
                    throw failure;
                }
            }
        }
        try {
            if (snapshot != null) {
                List<byte[]> loaded = new ArrayList<>(unresolved.size());
                try (var reads = new org.rocksdb.ReadOptions().setSnapshot(snapshot)) {
                    for (int offset = 0; offset < unresolved.size(); offset += 256) {
                        var batch = unresolved.subList(offset, Math.min(unresolved.size(), offset + 256));
                        nativeReadKeys.add(batch.size());
                        loaded.addAll(database.multiGetAsList(reads, batch));
                    }
                } finally {
                    // Never reacquire the database monitor while holding this
                    // lifetime lock: close owns that monitor while waiting here.
                    try { database.releaseSnapshot(snapshot); }
                    finally { warmReadLifetime.readLock().unlock(); }
                }
                synchronized (this) {
                    for (int i = 0; i < loaded.size(); i++) {
                        byte[] key = unresolved.get(i);
                        if (!closed && key.length > 0
                                && versions[Byte.toUnsignedInt(key[0])] == namespaceVersions[Byte.toUnsignedInt(key[0])]) {
                            metadataCache.remember(key, loaded.get(i));
                        }
                        result.set(positions.get(i), loaded.get(i));
                    }
                }
            }
            return result;
        } catch (RocksDBException failure) {
            throw new IllegalStateException("Failed to batch-read RocksDB values", failure);
        } finally {
            long elapsed = System.nanoTime() - started;
            getCount.add(keys.size());
            getNanos.add(elapsed);
            long share = elapsed / keys.size();
            long remainder = elapsed % keys.size();
            for (byte[] key : keys) {
                if (key.length == 0) continue;
                int namespace = Byte.toUnsignedInt(key[0]);
                namespaceGetCount[namespace].increment();
                namespaceGetNanos[namespace].add(share + (remainder-- > 0 ? 1 : 0));
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
            if (chainstateWriteBackEnabled || chainstateWriteBack != null) {
                ensureWriteBack().delete(key);
                metadataCache.invalidate(key);
                if (key.length > 0) namespaceVersions[Byte.toUnsignedInt(key[0])]++;
                flushChainstateIfNeeded();
                return;
            }
            database.delete(key);
            metadataCache.remember(key, null);
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

    public synchronized void enableChainstateWriteBack() {
        ensureOpen();
        chainstateWriteBackEnabled = true;
        ensureWriteBack();
    }

    public synchronized void disableChainstateWriteBack(boolean flush) {
        chainstateWriteBackEnabled = false;
        if (flush) forceFlushChainstate();
    }

    public synchronized boolean chainstateWriteBackEnabled() { return chainstateWriteBackEnabled; }

    public synchronized void flushChainstateIfNeeded() {
        if (chainstateWriteBack == null || chainstateWriteBack.operationCount() == 0) return;
        long age = System.nanoTime() - chainstateWriteBackStartedNanos;
        if (chainstateWriteBack.estimatedBytes() >= WRITE_BACK_MAX_BYTES
                || chainstateWriteBack.operationCount() >= WRITE_BACK_MAX_OPERATIONS
                || age >= WRITE_BACK_MAX_AGE_NANOS) forceFlushChainstate();
    }

    public synchronized void forceFlushChainstate() {
        if (chainstateWriteBack == null) return;
        // Flush payloads before the database checkpoint referring to them.
        flushPayloadFiles();
        RocksDbWriteBatch batch = chainstateWriteBack;
        chainstateWriteBack = null;
        if (batch.operationCount() == 0) { batch.close(); return; }
        long started = System.nanoTime();
        try (batch; WriteOptions options = new WriteOptions().setSync(true)) {
            database.write(options, batch.nativeBatch());
            batch.publishMetadata(metadataCache);
            writeBatchCount.increment(); syncWriteBatchCount.increment();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to flush chainstate write-back cache", e);
        } finally { writeBatchNanos.add(System.nanoTime() - started); }
        if (chainstateWriteBackEnabled) ensureWriteBack();
    }

    private RocksDbWriteBatch ensureWriteBack() {
        if (chainstateWriteBack == null) {
            chainstateWriteBack = new RocksDbWriteBatch();
            chainstateWriteBackStartedNanos = System.nanoTime();
        }
        return chainstateWriteBack;
    }

    public synchronized void syncWal() {
        ensureOpen();
        flushPayloadFiles();
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
            throw new IllegalArgumentException("batch must not be null");
        }
        if (chainstateWriteBackEnabled || chainstateWriteBack != null) {
            ensureWriteBack().appendFrom(batch);
            batch.invalidateMetadata(metadataCache);
            var prefixes = batch.changedPrefixes();
            for (int prefix = prefixes.nextSetBit(0); prefix >= 0; prefix = prefixes.nextSetBit(prefix + 1)) namespaceVersions[prefix]++;
            flushChainstateIfNeeded();
            return;
        }

        long started = System.nanoTime();
        try (WriteOptions writeOptions =
                     new WriteOptions()
                             .setSync(sync)) {

            if (sync) flushPayloadFiles();
            database.write(
                    writeOptions,
                    batch.nativeBatch()
            );
            batch.publishMetadata(metadataCache);
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
    public synchronized void close() {

        if (!closed) {
            forceFlushChainstate();
            flushPayloadFiles();
            payloadFiles.values().forEach(FlatFileRecordStore::close);
            chainstateWriteBackEnabled = false;
            closed = true;

            warmReadLifetime.writeLock().lock();
            try {
                database.close();
                options.close();
                bloomFilter.close();
                blockCache.close();
            } finally {
                warmReadLifetime.writeLock().unlock();
            }
        }
    }

    /** Read hints only: populate RocksDB's block cache, discard all coin values. */
    public void warmKeys(List<byte[]> keys) {
        java.util.Objects.requireNonNull(keys, "keys");
        for (int offset = 0; offset < keys.size(); offset += 512) {
            if (Thread.currentThread().isInterrupted()) return;
            List<byte[]> cold = new ArrayList<>();
            synchronized (this) {
                if (closed) return;
                for (byte[] key : keys.subList(offset, Math.min(keys.size(), offset + 512))) {
                    java.util.Objects.requireNonNull(key, "key");
                    if (chainstateWriteBack == null || !chainstateWriteBack.pendingValue(key).touched()) cold.add(key);
                }
                if (cold.isEmpty()) continue;
                warmReadLifetime.readLock().lock();
            }
            long started = System.nanoTime();
            try {
                database.multiGetAsList(cold);
            } catch (RocksDBException failure) {
                throw new IllegalStateException("Failed to warm RocksDB cache", failure);
            } finally {
                warmKeysCount.add(cold.size());
                warmReadNanos.add(System.nanoTime() - started);
                warmReadLifetime.readLock().unlock();
            }
        }
    }

    public record WarmReadStats(long keys, long nanos) { }
    public WarmReadStats warmReadStats() {
        return new WarmReadStats(warmKeysCount.sum(), warmReadNanos.sum());
    }
}
