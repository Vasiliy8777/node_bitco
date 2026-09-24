package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Objects;

public final class RocksDbBlockFailureStore
        implements BlockFailureStore {

    /*
     * Existing namespaces:
     *
     * 0x01 -> block index
     * 0x02 -> chain-state metadata
     *
     * Keep invalid-block state in its own namespace.
     */
    private static final byte FAILED_BLOCK_PREFIX =
            0x07;

    private static final byte[] FAILED_VALUE = {
            0x01
    };

    private final RocksDbDatabase database;

    public RocksDbBlockFailureStore(
            RocksDbDatabase database
    ) {
        this.database =
                Objects.requireNonNull(
                        database,
                        "database"
                );
    }

    @Override
    public long revision() {
        return database.namespaceVersion(FAILED_BLOCK_PREFIX);
    }

    @Override
    public boolean isFailed(
            Hash256 hash
    ) {
        Objects.requireNonNull(
                hash,
                "hash"
        );

        return database.get(
                key(hash)
        ) != null;
    }

    @Override
    public void markFailed(
            Hash256 hash
    ) {
        Objects.requireNonNull(
                hash,
                "hash"
        );

        database.put(
                key(hash),
                FAILED_VALUE
        );
    }

    public void markFailed(
            RocksDbWriteBatch batch,
            Hash256 hash
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(hash, "hash");
        batch.put(key(hash), FAILED_VALUE);
    }

    private static byte[] key(
            Hash256 hash
    ) {
        byte[] hashBytes =
                hash.bytes();

        byte[] key =
                new byte[
                        1 + hashBytes.length
                        ];

        key[0] =
                FAILED_BLOCK_PREFIX;

        System.arraycopy(
                hashBytes,
                0,
                key,
                1,
                hashBytes.length
        );

        return key;
    }
}
