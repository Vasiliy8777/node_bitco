package ru.bitcoin.node.crypto.filter;

import ru.bitcoin.node.common.types.Hash256;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Objects;

/**
 * BIP158 basic compact-block filter parameters and encoding.
 */
public final class BasicBlockFilter {
    public static final int P = 19;
    public static final long M = 784_931L;

    private BasicBlockFilter() {
    }

    public static byte[] encode(Hash256 blockHash, Collection<byte[]> elements) {
        Objects.requireNonNull(blockHash, "blockHash");
        byte[] hash = blockHash.bytes();
        long k0 = ByteBuffer.wrap(hash, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long k1 = ByteBuffer.wrap(hash, 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        return GolombCodedSet.encode(elements, k0, k1, P, M);
    }
}
