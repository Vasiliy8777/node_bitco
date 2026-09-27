package ru.bitcoin.node.storage.utxo;
import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class UtxoSnapshotMetadataTest {
    @Test void rejectsWrongNetworkAndTruncation() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(UtxoSnapshotWriter.MAGIC); out.write(2);out.write(0);
        out.write(new byte[]{(byte)0xfa,(byte)0xbf,(byte)0xb5,(byte)0xda}); out.write(new byte[32]); out.write(new byte[8]);
        byte[] data=out.toByteArray();
        var m=UtxoSnapshotMetadata.read(new ByteArrayInputStream(data),0xDAB5BFFAL); assertEquals(0,m.coinsCount()); assertEquals(new Hash256(new byte[32]),m.baseBlockHash());
        assertThrows(IOException.class,()->UtxoSnapshotMetadata.read(new ByteArrayInputStream(data),0xD9B4BEF9L));
        assertThrows(EOFException.class,()->UtxoSnapshotMetadata.read(new ByteArrayInputStream(new byte[]{'u'}),0xDAB5BFFAL));
    }
}
