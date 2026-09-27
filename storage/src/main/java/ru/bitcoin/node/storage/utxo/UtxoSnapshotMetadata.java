package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;

import java.io.*;
import java.util.*;

/**
 * Untrusted Bitcoin Core v2 UTXO snapshot metadata; validates magic/version/network before activation.
 */
public record UtxoSnapshotMetadata(long networkMagic, Hash256 baseBlockHash, long coinsCount) {
    public UtxoSnapshotMetadata {
        Objects.requireNonNull(baseBlockHash, "baseBlockHash");
        if (coinsCount < 0) throw new IllegalArgumentException("coinsCount exceeds signed long range");
    }

    public static UtxoSnapshotMetadata read(InputStream in, long expectedNetworkMagic) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] magic = readExact(in, 5);
        if (!Arrays.equals(magic, UtxoSnapshotWriter.MAGIC)) throw new IOException("Invalid UTXO snapshot magic");
        long version = readLE(in, 2);
        if (version != UtxoSnapshotWriter.VERSION)
            throw new IOException("Unsupported UTXO snapshot version: " + version);
        long network = readLE(in, 4);
        if (network != (expectedNetworkMagic & 0xffff_ffffL)) throw new IOException("UTXO snapshot network mismatch");
        Hash256 base = new Hash256(readExact(in, 32));
        long count = readLE(in, 8);
        if (count < 0) throw new IOException("UTXO snapshot coin count exceeds supported range");
        return new UtxoSnapshotMetadata(network, base, count);
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length != n) throw new EOFException("Truncated UTXO snapshot metadata");
        return b;
    }

    private static long readLE(InputStream in, int n) throws IOException {
        byte[] b = readExact(in, n);
        long v = 0;
        for (int i = 0; i < n; i++) v |= (b[i] & 255L) << (8 * i);
        return v;
    }
}
