package ru.bitcoin.node.storage.rocksdb;

/**
 * Canonical first-byte namespace allocation for RocksDB keys.
 *
 * Keep every namespace unique. A prefix is an ownership boundary: callers may
 * delete the complete prefix with RocksDbWriteBatch.deletePrefix(byte).
 */
public final class RocksDbNamespaces {
    private RocksDbNamespaces() {}

    public static final byte BLOCK_INDEX = 0x01;
    public static final byte CHAIN_STATE = 0x02;
    public static final byte UTXO = 0x03;
    public static final byte UNDO = 0x04;
    public static final byte BLOCK = 0x05;
    public static final byte BLOCK_FAILURE = 0x07;
    public static final byte BLOCK_WORK_INDEX = 0x08;
    public static final byte BLOCK_WORK_INDEX_VERSION = 0x09;
    public static final byte CHAINSTATE_REINDEX_STATE = 0x0A;
    public static final byte PRUNE_STATE = 0x0B;
    public static final byte FULL_REINDEX_STATE = 0x0C;
    public static final byte MEMPOOL = 0x0D;
    public static final byte TX_INDEX = 0x0E;
    public static final byte TX_INDEX_STATE = 0x0F;
    public static final byte BLOCK_AVAILABILITY = 0x10;
    public static final byte BLOCK_VALIDATION_STATUS = 0x11;
    public static final byte PRUNE_BLOCK_SIZE = 0x12;
    public static final byte PRUNE_UNDO_SIZE = 0x13;
    public static final byte BLOCK_HEIGHT_INDEX = 0x14;
    public static final byte BLOCK_HEIGHT_INDEX_VERSION = 0x15;
    public static final byte PRUNE_USAGE_VERSION = 0x16;

    /** Legacy v1 prune-usage marker that collided with BLOCK_HEIGHT_INDEX. */
    public static final byte[] LEGACY_PRUNE_USAGE_VERSION_KEY = {BLOCK_HEIGHT_INDEX, 0x01};

    public static byte[] singletonKey(byte namespace) {
        return new byte[]{namespace};
    }
}
