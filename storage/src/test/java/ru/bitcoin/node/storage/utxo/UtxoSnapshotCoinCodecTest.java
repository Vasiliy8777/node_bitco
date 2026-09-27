package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class UtxoSnapshotCoinCodecTest {
    @Test
    void roundTripsRawAndSpecialScripts() throws Exception {
        byte[] pkh = new byte[25];
        pkh[0] = 0x76;
        pkh[1] = (byte) 0xa9;
        pkh[2] = 20;
        for (int i = 0; i < 20; i++) pkh[3 + i] = (byte) i;
        pkh[23] = (byte) 0x88;
        pkh[24] = (byte) 0xac;
        for (byte[] script : new byte[][]{{0x51}, pkh}) {
            var coin = new StoredUtxo(123_450_000L, script, 123, true);
            var out = new ByteArrayOutputStream();
            UtxoSnapshotCoinCodec.write(out, coin);
            var decoded = UtxoSnapshotCoinCodec.read(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(coin.amount(), decoded.amount());
            assertEquals(coin.height(), decoded.height());
            assertEquals(coin.coinbase(), decoded.coinbase());
            assertArrayEquals(script, decoded.scriptPubKey());
        }
    }

    @Test
    void p2pkhUsesCoreSpecialEncoding() {
        byte[] s = new byte[25];
        s[0] = 0x76;
        s[1] = (byte) 0xa9;
        s[2] = 20;
        s[23] = (byte) 0x88;
        s[24] = (byte) 0xac;
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> UtxoSnapshotCoinCodec.writeScript(out, s));
        assertEquals(21, out.size());
        assertEquals(0, out.toByteArray()[0]);
    }
}
