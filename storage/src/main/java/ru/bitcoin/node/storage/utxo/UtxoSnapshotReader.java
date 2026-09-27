package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.io.*;
import java.util.Objects;

/**
 * Strict streaming reader for Bitcoin Core UTXO snapshot v2 bodies.
 */
public final class UtxoSnapshotReader {
    private UtxoSnapshotReader() {
    }

    @FunctionalInterface
    public interface CoinConsumer {
        void accept(OutPoint outPoint, StoredUtxo coin) throws IOException;
    }

    public record Result(UtxoSnapshotMetadata metadata, long coinsRead) {
    }

    public static Result read(InputStream input, long networkMagic, long baseHeight, CoinConsumer consumer) throws IOException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(consumer);
        UtxoSnapshotMetadata metadata = UtxoSnapshotMetadata.read(input, networkMagic);
        long left = metadata.coinsCount(), read = 0;
        while (left > 0) {
            Hash256 txid = new Hash256(UtxoSnapshotCoinCodec.readExactly(input, 32));
            long perTx = readCompactSize(input);
            if (perTx <= 0 || perTx > left) throw new IOException("Mismatch in snapshot coin count");
            long previous = -1;
            for (long i = 0; i < perTx; i++) {
                long vout = readCompactSize(input);
                if (vout >= 0xffff_ffffL) throw new IOException("Invalid snapshot vout");
                if (vout <= previous) throw new IOException("Snapshot vouts are not strictly increasing");
                previous = vout;
                StoredUtxo coin = UtxoSnapshotCoinCodec.read(input);
                if (coin.height() > baseHeight) throw new IOException("Snapshot coin height exceeds base height");
                consumer.accept(new OutPoint(txid, new UInt32(vout)), coin);
                left--;
                read++;
            }
        }
        if (input.read() != -1) throw new IOException("Snapshot has trailing data");
        return new Result(metadata, read);
    }

    static long readCompactSize(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) throw new EOFException();
        if (first < 253) return first;
        int bytes = first == 253 ? 2 : first == 254 ? 4 : 8;
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            v |= (long) b << (8 * i);
        }
        if ((first == 253 && v < 253) || (first == 254 && v <= 0xffffL) || (first == 255 && Long.compareUnsigned(v, 0xffff_ffffL) <= 0))
            throw new IOException("Non-canonical CompactSize");
        if (v < 0) throw new IOException("CompactSize exceeds signed long range");
        return v;
    }
}
