package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.encoding.CompactSize;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Bitcoin Core kernel/coinstats.cpp TxOutSer-compatible serialization.
 * <p>
 * This is deliberately NOT the on-disk Coin serialization from coins.h.
 * Coin uses VARINT(height/coinbase) plus TxOutCompression on disk, while
 * gettxoutsetinfo hash_serialized_3 and MuHash commit to:
 * <p>
 * txid || uint32(vout) || uint32(height * 2 + coinbase) || CTxOut
 * <p>
 * where both uint32 values and CTxOut::nValue are little-endian and the
 * script is serialized with CompactSize length.
 */
public final class CoreCoinStatsSerializer {
    private CoreCoinStatsSerializer() {
    }

    public static byte[] serialize(byte[] txid, long vout, StoredUtxo coin) {
        Objects.requireNonNull(txid, "txid");
        Objects.requireNonNull(coin, "coin");
        if (txid.length != 32) throw new IllegalArgumentException("txid must contain 32 bytes");
        if (vout < 0 || vout > 0xffff_ffffL) throw new IllegalArgumentException("vout out of uint32 range");
        if (coin.height() < 0 || coin.height() > 0x7fff_ffffL)
            throw new IllegalArgumentException("height cannot be encoded in uint32 height/coinbase metadata");

        long metadata = Math.addExact(Math.multiplyExact(coin.height(), 2L), coin.coinbase() ? 1L : 0L);
        byte[] script = coin.scriptPubKey();
        ByteArrayOutputStream stream = new ByteArrayOutputStream(48 + script.length);
        stream.writeBytes(txid);
        writeUInt32LittleEndian(stream, vout);
        writeUInt32LittleEndian(stream, metadata);
        writeInt64LittleEndian(stream, coin.amount());
        stream.writeBytes(CompactSize.encode(script.length));
        stream.writeBytes(script);
        return stream.toByteArray();
    }

    private static void writeUInt32LittleEndian(ByteArrayOutputStream out, long value) {
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("uint32 out of range");
        for (int i = 0; i < 4; i++) out.write((byte) (value >>> (8 * i)));
    }

    private static void writeInt64LittleEndian(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((byte) (value >>> (8 * i)));
    }
}
