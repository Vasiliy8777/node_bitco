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
    private final FlatFileRecordStore files;

    public RocksDbBlockStore(RocksDbDatabase database) {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        this.database=database;
        this.files=database.payloadFiles("blk");
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
            batch.put(metadataKey, existing);
            new RocksDbBlockAvailabilityStore(database).markData(batch, hash);
            new RocksDbPruneUsageStore(database).setBlockSize(batch, hash, position.payloadLength());
            return;
        }

        byte[] serialized=BlockSerializer.serialize(block);
        FlatFileRecordStore.Position position=files.append(serialized);
        batch.put(metadataKey,position.serialize());
        new RocksDbBlockAvailabilityStore(database).markData(batch,hash);
        new RocksDbPruneUsageStore(database).setBlockSize(batch,hash,serialized.length);
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

    public long serializedSize(Hash256 hash){ if(hash==null) throw new IllegalArgumentException("blockHash must not be null"); return new RocksDbPruneUsageStore(database).blockSize(hash); }
    @Override public void delete(Hash256 hash){ if(hash==null) throw new IllegalArgumentException("blockHash must not be null"); try(var batch=new RocksDbWriteBatch()){delete(batch,hash);database.write(batch);} }
    public void delete(RocksDbWriteBatch batch,Hash256 hash){
        if(batch==null) throw new IllegalArgumentException("batch must not be null"); if(hash==null) throw new IllegalArgumentException("blockHash must not be null");
        // Core-style flat files are append-only. Pruning drops metadata; physical file compaction/deletion is file-granular.
        batch.delete(key(hash)); new RocksDbBlockAvailabilityStore(database).clearData(batch,hash); new RocksDbPruneUsageStore(database).setBlockSize(batch,hash,0L);
    }
    private static byte[] key(Hash256 hash){ byte[] h=hash.bytes(); if(h.length!=HASH_SIZE) throw new IllegalStateException("Invalid block hash length: "+h.length); byte[] k=new byte[1+HASH_SIZE]; k[0]=BLOCK_PREFIX; System.arraycopy(h,0,k,1,HASH_SIZE); return k; }
}
