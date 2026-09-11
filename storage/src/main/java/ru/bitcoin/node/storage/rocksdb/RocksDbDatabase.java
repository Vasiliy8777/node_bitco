package ru.bitcoin.node.storage.rocksdb;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.nio.file.Path;

public final class RocksDbDatabase
        implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;

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
            options =
                    new Options()
                            .setCreateIfMissing(true);

            database =
                    RocksDB.open(
                            options,
                            databasePath.toString()
                    );

        } catch (RocksDBException e) {

            throw new IllegalStateException(
                    "Failed to open RocksDB: "
                            + databasePath,
                    e
            );
        }
    }

    public void put(
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

    public void delete(
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

        } catch (RocksDBException e) {

            throw new IllegalStateException(
                    "Failed to write RocksDB batch",
                    e
            );
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