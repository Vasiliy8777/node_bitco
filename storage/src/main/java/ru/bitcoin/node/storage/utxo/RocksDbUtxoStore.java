package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

public final class RocksDbUtxoStore
        implements UtxoStore {

    private static final byte UTXO_PREFIX = 0x03;

    /** Diagnostic full count; requires a stable chain snapshot. */
    public long count() { return database.countPrefix(UTXO_PREFIX); }

    private static final int TXID_SIZE = 32;
    private static final int VOUT_SIZE = 4;

    private final RocksDbDatabase database;

    public RocksDbUtxoStore(
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
    public void save(
            OutPoint outPoint,
            StoredUtxo utxo
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        database.put(
                key(outPoint),
                StoredUtxoSerializer.serialize(
                        utxo
                )
        );
    }

    @Override
    public Optional<StoredUtxo> find(
            OutPoint outPoint
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        byte[] value =
                database.get(
                        key(outPoint)
                );

        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(
                StoredUtxoSerializer.deserialize(
                        value
                )
        );
    }

    @Override
    public void delete(
            OutPoint outPoint
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        database.delete(
                key(outPoint)
        );
    }

    private static byte[] key(
            OutPoint outPoint
    ) {
        byte[] txid =
                outPoint.transactionId().bytes();

        if (txid.length != TXID_SIZE) {
            throw new IllegalStateException(
                    "Invalid txid length: "
                            + txid.length
            );
        }

        byte[] key =
                new byte[
                        1
                                + TXID_SIZE
                                + VOUT_SIZE
                        ];

        key[0] =
                UTXO_PREFIX;

        System.arraycopy(
                txid,
                0,
                key,
                1,
                TXID_SIZE
        );

        long index =
                outPoint.outputIndex().value();

        int offset =
                1 + TXID_SIZE;

        key[offset] =
                (byte) (
                        index & 0xFF
                );

        key[offset + 1] =
                (byte) (
                        (index >>> 8) & 0xFF
                );

        key[offset + 2] =
                (byte) (
                        (index >>> 16) & 0xFF
                );

        key[offset + 3] =
                (byte) (
                        (index >>> 24) & 0xFF
                );

        return key;
    }
    public void save(
            RocksDbWriteBatch batch,
            OutPoint outPoint,
            StoredUtxo utxo
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        batch.put(
                key(outPoint),
                StoredUtxoSerializer.serialize(
                        utxo
                )
        );
    }

    public void delete(
            RocksDbWriteBatch batch,
            OutPoint outPoint
    ) {
        if (batch == null) {
            throw new IllegalArgumentException(
                    "batch must not be null"
            );
        }

        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        batch.delete(
                key(outPoint)
        );
    }
    /** Removes the complete persistent namespace in the caller's atomic batch. */
    public void clear(RocksDbWriteBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.deletePrefix(UTXO_PREFIX);
    }

}
