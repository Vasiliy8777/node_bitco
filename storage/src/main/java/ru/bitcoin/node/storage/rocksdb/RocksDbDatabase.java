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

public final class RocksDbDatabase
        implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final long[] namespaceVersions = new long[256];

    private boolean closed;

    public RocksDbDatabase(
            Path databasePath
    ) {
        if (databasePath == null) {
            throw new IllegalArgumentException(
                    "databasePath must not be null"
            );
        }

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

        try {
            return database.get(
                    key
            );
        } catch (RocksDBException e) {
            throw new IllegalStateException(
                    "Failed to read RocksDB value",
                    e
            );
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
    public synchronized void write(
            RocksDbWriteBatch batch
    ) {
        ensureOpen();

        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        try (WriteOptions writeOptions =
                     new WriteOptions()
                             .setSync(true)) {

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
