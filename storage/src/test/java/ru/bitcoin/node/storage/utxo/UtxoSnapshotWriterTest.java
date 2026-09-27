package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class UtxoSnapshotWriterTest {
    @TempDir Path temp;
    @Test void writesCoreV2MetadataAndGroupsCoinsByTxid() throws Exception {
        byte[] a=new byte[32]; a[0]=1; byte[] b=new byte[32]; b[0]=2;
        Hash256 base=new Hash256(new byte[32]);
        Path out=temp.resolve("utxo.dat");
        try(var db=new RocksDbDatabase(temp.resolve("db"))){
            var store=new RocksDbUtxoStore(db);
            store.save(new OutPoint(new Hash256(a),new UInt32(0)),new StoredUtxo(1000,new byte[]{0x51},5,false));
            store.save(new OutPoint(new Hash256(a),new UInt32(2)),new StoredUtxo(2000,new byte[]{0x52},6,true));
            store.save(new OutPoint(new Hash256(b),new UInt32(1)),new StoredUtxo(3000,new byte[]{0x53},7,false));
            var result=UtxoSnapshotWriter.write(db,0xDAB5BFFAL,base,7,out);
            assertEquals(3,result.coinsWritten()); assertEquals(7,result.baseHeight());
        }
        byte[] bytes=Files.readAllBytes(out);
        assertArrayEquals(UtxoSnapshotWriter.MAGIC,Arrays.copyOfRange(bytes,0,5));
        assertEquals(2,(bytes[5]&255)|((bytes[6]&255)<<8));
        assertArrayEquals(new byte[]{(byte)0xfa,(byte)0xbf,(byte)0xb5,(byte)0xda},Arrays.copyOfRange(bytes,7,11));
        long count=0; for(int i=0;i<8;i++)count|=(bytes[43+i]&255L)<<(8*i); assertEquals(3,count);
        assertTrue(bytes.length>51);
    }
    @Test void refusesOverwrite() throws Exception {
        Path out=temp.resolve("exists.dat"); Files.writeString(out,"x");
        try(var db=new RocksDbDatabase(temp.resolve("db2"))){
            assertThrows(FileAlreadyExistsException.class,()->UtxoSnapshotWriter.write(db,0,new Hash256(new byte[32]),0,out));
        }
    }
    @Test void amountCompressionMatchesCoreExamples(){
        assertEquals(0,UtxoSnapshotWriter.compressAmount(0));
        assertEquals(1,UtxoSnapshotWriter.compressAmount(1));
        assertEquals(2,UtxoSnapshotWriter.compressAmount(10));
        assertEquals(3,UtxoSnapshotWriter.compressAmount(100));
        assertEquals(4,UtxoSnapshotWriter.compressAmount(1000));
    }
}
