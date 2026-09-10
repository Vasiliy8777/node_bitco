package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.util.Objects;

public final class BlockIndex {

    private final Hash256 hash;
    private final BlockHeader header;

    private final long height;

    private final Hash256 previousBlockHash;

    private final BigInteger chainWork;

    public BlockIndex(
            Hash256 hash,
            BlockHeader header,
            long height,
            Hash256 previousBlockHash,
            BigInteger chainWork
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (height < 0) {
            throw new IllegalArgumentException(
                    "height must not be negative"
            );
        }

        if (previousBlockHash == null) {
            throw new IllegalArgumentException(
                    "previousBlockHash must not be null"
            );
        }

        if (chainWork == null) {
            throw new IllegalArgumentException(
                    "chainWork must not be null"
            );
        }

        if (chainWork.signum() < 0) {
            throw new IllegalArgumentException(
                    "chainWork must not be negative"
            );
        }

        this.hash = hash;
        this.header = header;
        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.chainWork = chainWork;
    }

    public Hash256 hash() {
        return hash;
    }

    public BlockHeader header() {
        return header;
    }

    public long height() {
        return height;
    }

    public Hash256 previousBlockHash() {
        return previousBlockHash;
    }

    public BigInteger chainWork() {
        return chainWork;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof BlockIndex that)) {
            return false;
        }

        return hash.equals(that.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash);
    }

    @Override
    public String toString() {
        return "BlockIndex{" +
                "hash=" + hash.toDisplayHex() +
                ", height=" + height +
                ", chainWork=" + chainWork +
                '}';
    }
}
