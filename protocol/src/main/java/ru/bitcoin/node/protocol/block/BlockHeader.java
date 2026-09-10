package ru.bitcoin.node.protocol.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;

public final class BlockHeader {

    public static final int SERIALIZED_SIZE = 80;

    private final int version;
    private final Hash256 previousBlockHash;
    private final Hash256 merkleRoot;
    private final UInt32 timestamp;
    private final UInt32 bits;
    private final UInt32 nonce;

    public BlockHeader(
            int version,
            Hash256 previousBlockHash,
            Hash256 merkleRoot,
            UInt32 timestamp,
            UInt32 bits,
            UInt32 nonce
    ) {
        if (previousBlockHash == null) {
            throw new IllegalArgumentException(
                    "previousBlockHash must not be null"
            );
        }

        if (merkleRoot == null) {
            throw new IllegalArgumentException(
                    "merkleRoot must not be null"
            );
        }

        if (timestamp == null) {
            throw new IllegalArgumentException(
                    "timestamp must not be null"
            );
        }

        if (bits == null) {
            throw new IllegalArgumentException(
                    "bits must not be null"
            );
        }

        if (nonce == null) {
            throw new IllegalArgumentException(
                    "nonce must not be null"
            );
        }

        this.version = version;
        this.previousBlockHash = previousBlockHash;
        this.merkleRoot = merkleRoot;
        this.timestamp = timestamp;
        this.bits = bits;
        this.nonce = nonce;
    }

    public int version() {
        return version;
    }

    public Hash256 previousBlockHash() {
        return previousBlockHash;
    }

    public Hash256 merkleRoot() {
        return merkleRoot;
    }

    public UInt32 timestamp() {
        return timestamp;
    }

    public UInt32 bits() {
        return bits;
    }

    public UInt32 nonce() {
        return nonce;
    }

    public Hash256 hash() {
        return Hash256Digest.hash(
                BlockHeaderSerializer.serialize(this)
        );
    }
}
