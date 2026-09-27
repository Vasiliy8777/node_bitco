package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CoreCoinStatsSerializerTest {
    @Test
    void serializesCoreTxOutSerPreimageByteForByte() {
        byte[] txid = new byte[32];
        var coin = new StoredUtxo(500L, new byte[]{0x51}, 63L, true);

        assertEquals(
                "00".repeat(32)
                        + "01000000"       // COutPoint::n
                        + "7f000000"       // uint32(height * 2 + coinbase) = 127
                        + "f401000000000000" // CTxOut::nValue = 500
                        + "01"             // CompactSize(script length)
                        + "51",
                HexFormat.of().formatHex(CoreCoinStatsSerializer.serialize(txid, 1L, coin))
        );
    }

    @Test
    void heightMetadataIsFixedUint32NotCoinDiskVarInt() {
        byte[] serialized = CoreCoinStatsSerializer.serialize(
                new byte[32], 0L, new StoredUtxo(1L, new byte[0], 64L, false));

        // offset 36 follows 32-byte txid + 4-byte vout. 64*2 = 128.
        assertArrayEquals(new byte[]{(byte) 0x80, 0x00, 0x00, 0x00},
                java.util.Arrays.copyOfRange(serialized, 36, 40));
    }

    @Test
    void acceptsMaximumCoreCoinHeightAndRejectsOverflow() {
        assertDoesNotThrow(() -> CoreCoinStatsSerializer.serialize(
                new byte[32], 0xffff_ffffL,
                new StoredUtxo(0L, new byte[0], 0x7fff_ffffL, true)));

        assertThrows(IllegalArgumentException.class, () -> CoreCoinStatsSerializer.serialize(
                new byte[32], 0L,
                new StoredUtxo(0L, new byte[0], 0x8000_0000L, false)));
    }
}
