package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;
import ru.bitcoin.node.storage.rocksdb.FlatFileRecordStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Optional;

/**
 * Block payload store. Despite the historical class name, block bodies live in Core-style
 * blocks/blkNNNNN.dat files; RocksDB contains only hash -> flat-file position metadata.
 */
public final class RocksDbBlockStore implements BlockStore {
    private static final byte BLOCK_PREFIX = 0x05;
    private static final int HASH_SIZE = 32;
    private final RocksDbDatabase database;
    private final FlatFileRecordStore files; private final RocksDbPruneUsageStore pruneUsage;

    public RocksDbBlockStore(RocksDbDatabase database) {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        this.database=database;
        this.files=database.payloadFiles("blk"); this.pruneUsage=new RocksDbPruneUsageStore(database);
    }

    @Override public void save(Block block) {
        if(block==null) throw new IllegalArgumentException("block must not be null");
        try(var batch=new RocksDbWriteBatch()){ save(batch,block); database.write(batch); }
    }

    public void save(RocksDbWriteBatch batch, Block block) {
        if(batch==null) throw new IllegalArgumentException("batch must not be null");
        if(block==null) throw new IllegalArgumentException("block must not be null");
        Hash256 hash=block.header().hash();
        byte[] metadataKey = key(hash);

        // A save can follow an availability-prefix clear in the same batch.  The block payload
        // itself is append-only, so reuse an already published (or already staged) flat-file
        // position instead of appending a duplicate, but still compose all derived metadata
        // into the caller's batch.
        RocksDbWriteBatch.PendingValue pending = batch.pendingValue(metadataKey);
        byte[] existing = pending.touched() && pending.value() != null
                ? pending.value()
                : database.get(metadataKey);
        if (existing != null) {
            FlatFileRecordStore.Position position = FlatFileRecordStore.Position.deserialize(existing);
            var pendingCount = batch.pendingValue(countKey(hash));
            byte[] count = pendingCount.touched() ? pendingCount.value() : database.get(countKey(hash));
            if (count == null) count = ru.bitcoin.node.common.bytes.LittleEndian.uint32(BlockParser.parse(files.read(position)).transactions().size());
            batch.put(countKey(hash), count);
            batch.put(metadataKey, existing);
            new RocksDbBlockAvailabilityStore(database).markData(batch, hash);
            pruneUsage.setBlockSize(batch, hash, position.payloadLength());
            return;
        }

        byte[] serialized=BlockSerializer.serialize(block);
        FlatFileRecordStore.Position position=files.append(serialized);
        batch.put(countKey(hash), ru.bitcoin.node.common.bytes.LittleEndian.uint32(block.transactions().size()));
        batch.put(metadataKey,position.serialize());
        new RocksDbBlockAvailabilityStore(database).markData(batch,hash);
        pruneUsage.setBlockSize(batch,hash,serialized.length);
    }

    @Override public Optional<Block> find(Hash256 hash) {
        if(hash==null) throw new IllegalArgumentException("blockHash must not be null");
        byte[] metadata=database.get(key(hash));
        if(metadata==null) return Optional.empty();
        Block block=BlockParser.parse(files.read(FlatFileRecordStore.Position.deserialize(metadata)));
        if(!block.header().hash().equals(hash)) throw new IllegalStateException("Stored block hash mismatch");
        return Optional.of(block);
    }

    public void forEachHeader(java.util.function.Consumer<ru.bitcoin.node.protocol.block.BlockHeader> visitor) {
        java.util.Objects.requireNonNull(visitor,"visitor");
        database.forEachValueByPrefix(BLOCK_PREFIX, metadata -> {
            byte[] value=files.read(FlatFileRecordStore.Position.deserialize(metadata));
            if(value.length<ru.bitcoin.node.protocol.block.BlockHeader.SERIALIZED_SIZE) throw new IllegalStateException("Stored block is shorter than an 80-byte header");
            visitor.accept(ru.bitcoin.node.protocol.serialization.BlockHeaderParser.parse(java.util.Arrays.copyOf(value,ru.bitcoin.node.protocol.block.BlockHeader.SERIALIZED_SIZE)));
        });
    }

    public long serializedSize(Hash256 hash){ if(hash==null) throw new IllegalArgumentException("blockHash must not be null"); return pruneUsage.blockSize(hash); }
    /** Transaction count survives pruning. Existing unpruned blocks are migrated lazily. */
    public java.util.OptionalLong transactionCount(Hash256 hash) {
        byte[] value = database.get(countKey(hash));
        if (value != null) {
            if (value.length != 4) throw new IllegalStateException("Invalid block transaction count");
            return java.util.OptionalLong.of(ru.bitcoin.node.common.bytes.LittleEndian.readUInt32(value, 0));
        }
        var block = find(hash);
        if (block.isEmpty()) return java.util.OptionalLong.empty();
        long count = block.orElseThrow().transactions().size();
        database.put(countKey(hash), ru.bitcoin.node.common.bytes.LittleEndian.uint32(count));
        return java.util.OptionalLong.of(count);
    }
    @Override public void delete(Hash256 hash){ if(hash==null) throw new IllegalArgumentException("blockHash must not be null"); try(var batch=new RocksDbWriteBatch()){delete(batch,hash);database.write(batch);} }
    public void delete(RocksDbWriteBatch batch,Hash256 hash){
        if(batch==null) throw new IllegalArgumentException("batch must not be null"); if(hash==null) throw new IllegalArgumentException("blockHash must not be null");
        // Core-style flat files are append-only. Pruning drops metadata; physical file compaction/deletion is file-granular.
        if (!batch.pendingValue(countKey(hash)).touched() && database.get(countKey(hash)) == null)
            find(hash).ifPresent(block -> batch.put(countKey(hash), ru.bitcoin.node.common.bytes.LittleEndian.uint32(block.transactions().size())));
        batch.delete(key(hash)); new RocksDbBlockAvailabilityStore(database).clearData(batch,hash); pruneUsage.setBlockSize(batch,hash,0L);
    }
    private static byte[] countKey(Hash256 hash) { byte[] key = key(hash); key[0] = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_TX_COUNT; return key; }
    private static byte[] key(Hash256 hash){ byte[] h=hash.bytes(); if(h.length!=HASH_SIZE) throw new IllegalStateException("Invalid block hash length: "+h.length); byte[] k=new byte[1+HASH_SIZE]; k[0]=BLOCK_PREFIX; System.arraycopy(h,0,k,1,HASH_SIZE); return k; }
}
