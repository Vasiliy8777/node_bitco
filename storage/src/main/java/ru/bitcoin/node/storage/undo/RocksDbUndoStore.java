package ru.bitcoin.node.storage.undo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.chain.RocksDbPruneUsageStore;
import ru.bitcoin.node.storage.rocksdb.FlatFileRecordStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import java.util.Optional;

/** Undo payloads in blocks/revNNNNN.dat; RocksDB stores only hash -> file position metadata. */
public final class RocksDbUndoStore implements UndoStore {
    private static final byte UNDO_PREFIX=0x04; private static final int HASH_SIZE=32;
    private final RocksDbDatabase database; private final FlatFileRecordStore files;
    public RocksDbUndoStore(RocksDbDatabase database){ if(database==null)throw new IllegalArgumentException("database must not be null"); this.database=database; this.files=new FlatFileRecordStore(database.externalDataRoot().resolve("blocks"),"rev",database.networkMagic()); }
    @Override public void save(Hash256 hash,BlockUndoData data){ if(hash==null)throw new IllegalArgumentException("blockHash must not be null"); if(data==null)throw new IllegalArgumentException("undoData must not be null"); try(var batch=new RocksDbWriteBatch()){save(batch,hash,data);database.write(batch);} }
    @Override public Optional<BlockUndoData> find(Hash256 hash){ if(hash==null)throw new IllegalArgumentException("blockHash must not be null"); byte[] m=database.get(key(hash)); return m==null?Optional.empty():Optional.of(BlockUndoDataSerializer.deserialize(files.read(FlatFileRecordStore.Position.deserialize(m)))); }
    public long serializedSize(Hash256 hash){if(hash==null)throw new IllegalArgumentException("blockHash must not be null");return new RocksDbPruneUsageStore(database).undoSize(hash);}
    @Override public void delete(Hash256 hash){if(hash==null)throw new IllegalArgumentException("blockHash must not be null");try(var batch=new RocksDbWriteBatch()){delete(batch,hash);database.write(batch);}}
    public void save(RocksDbWriteBatch batch,Hash256 hash,BlockUndoData data){
        if(batch==null)throw new IllegalArgumentException("batch must not be null");if(hash==null)throw new IllegalArgumentException("blockHash must not be null");if(data==null)throw new IllegalArgumentException("undoData must not be null");
        byte[] metadataKey=key(hash);
        RocksDbWriteBatch.PendingValue pending=batch.pendingValue(metadataKey);
        byte[] existing=pending.touched()&&pending.value()!=null?pending.value():database.get(metadataKey);
        if(existing!=null){
            var pos=FlatFileRecordStore.Position.deserialize(existing);
            batch.put(metadataKey,existing);
            new RocksDbBlockAvailabilityStore(database).markUndo(batch,hash);
            new RocksDbPruneUsageStore(database).setUndoSize(batch,hash,pos.payloadLength());
            return;
        }
        byte[] serialized=BlockUndoDataSerializer.serialize(data); var pos=files.append(serialized); batch.put(metadataKey,pos.serialize()); new RocksDbBlockAvailabilityStore(database).markUndo(batch,hash); new RocksDbPruneUsageStore(database).setUndoSize(batch,hash,serialized.length);
    }
    public void delete(RocksDbWriteBatch batch,Hash256 hash){if(batch==null)throw new IllegalArgumentException("batch must not be null");if(hash==null)throw new IllegalArgumentException("blockHash must not be null");batch.delete(key(hash));new RocksDbBlockAvailabilityStore(database).clearUndo(batch,hash);new RocksDbPruneUsageStore(database).setUndoSize(batch,hash,0L);}
    public void clear(RocksDbWriteBatch batch){if(batch==null)throw new IllegalArgumentException("batch must not be null");batch.deletePrefix(UNDO_PREFIX);new RocksDbPruneUsageStore(database).clearUndoSizes(batch);}
    private static byte[] key(Hash256 hash){byte[] h=hash.bytes();if(h.length!=HASH_SIZE)throw new IllegalStateException("Invalid block hash length: "+h.length);byte[] k=new byte[1+HASH_SIZE];k[0]=UNDO_PREFIX;System.arraycopy(h,0,k,1,HASH_SIZE);return k;}
}
