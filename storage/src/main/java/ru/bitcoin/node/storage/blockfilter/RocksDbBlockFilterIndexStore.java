package ru.bitcoin.node.storage.blockfilter;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;
import java.util.Optional;

/** Persistent BIP158 basic filter, filter-header and active synchronization cursor. */
public final class RocksDbBlockFilterIndexStore {
    private static final byte FILTER = RocksDbNamespaces.BLOCK_FILTER_INDEX;
    private static final byte HEADER = RocksDbNamespaces.BLOCK_FILTER_HEADER_INDEX;
    private static final byte STATE = RocksDbNamespaces.BLOCK_FILTER_INDEX_STATE;
    private static final byte[] STATE_KEY = {STATE};
    private final RocksDbDatabase database;

    public RocksDbBlockFilterIndexStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<Record> find(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        byte[] filter = database.get(key(FILTER, blockHash));
        if (filter == null) return Optional.empty();
        byte[] header = database.get(key(HEADER, blockHash));
        if (header == null || header.length != Hash256.LENGTH)
            throw new IllegalStateException("Missing/invalid block-filter header for " + blockHash.toDisplayHex());
        return Optional.of(new Record(filter, new Hash256(header)));
    }

    public Optional<Hash256> bestIndexedBlockHash() {
        byte[] value = database.get(STATE_KEY);
        if (value == null) return Optional.empty();
        if (value.length != Hash256.LENGTH) throw new IllegalStateException("Invalid block-filter cursor length");
        return Optional.of(new Hash256(value));
    }

    public void append(Hash256 blockHash, byte[] filter, Hash256 filterHeader) {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filterHeader, "filterHeader");
        try (var batch = new RocksDbWriteBatch()) {
            batch.put(key(FILTER, blockHash), filter);
            batch.put(key(HEADER, blockHash), filterHeader.bytes());
            batch.put(STATE_KEY, blockHash.bytes());
            database.write(batch);
        }
    }

    public void moveCursor(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        if (find(blockHash).isEmpty()) throw new IllegalStateException("Cannot move block-filter cursor to unindexed block");
        database.put(STATE_KEY, blockHash.bytes());
    }

    public boolean initialized() { return bestIndexedBlockHash().isPresent(); }

    public void clear() {
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(FILTER);
            batch.deletePrefix(HEADER);
            batch.deletePrefix(STATE);
            database.write(batch);
        }
    }

    private static byte[] key(byte prefix, Hash256 hash) {
        byte[] bytes = hash.bytes();
        byte[] key = new byte[1 + bytes.length];
        key[0] = prefix;
        System.arraycopy(bytes, 0, key, 1, bytes.length);
        return key;
    }

    public record Record(byte[] filter, Hash256 header) {
        public Record {
            filter = Objects.requireNonNull(filter, "filter").clone();
            Objects.requireNonNull(header, "header");
        }
        @Override public byte[] filter() { return filter.clone(); }
    }
}
