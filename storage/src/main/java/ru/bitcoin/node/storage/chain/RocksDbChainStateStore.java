package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.bytes.ByteUtils;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

public final class RocksDbChainStateStore
        implements ChainStateStore {

    private static final byte[] ACTIVE_TIP_KEY = {
            0x02,
            0x01
    };

    private static final int HASH_SIZE = 32;

    private final RocksDbDatabase database;

    public RocksDbChainStateStore(
            RocksDbDatabase database
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        this.database = database;
    }

    @Override
    public Optional<Hash256> loadActiveTipHash() {

        byte[] value =
                database.get(
                        ACTIVE_TIP_KEY
                );

        if (value == null) {
            return Optional.empty();
        }

        if (value.length != HASH_SIZE) {
            throw new IllegalStateException(
                    "Invalid active tip hash size: "
                            + value.length
            );
        }

        return Optional.of(
                fromRawBytes(value)
        );
    }

    @Override
    public void saveActiveTipHash(
            Hash256 hash
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        database.put(
                ACTIVE_TIP_KEY,
                hash.bytes()
        );
    }

    private static Hash256 fromRawBytes(
            byte[] bytes
    ) {
        return Hash256.fromDisplayHex(
                HexUtils.encode(
                        ByteUtils.reverse(bytes)
                )
        );
    }
    public void saveActiveTipHash(
            RocksDbWriteBatch batch,
            Hash256 hash
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        batch.put(
                ACTIVE_TIP_KEY,
                hash.bytes()
        );
    }
}