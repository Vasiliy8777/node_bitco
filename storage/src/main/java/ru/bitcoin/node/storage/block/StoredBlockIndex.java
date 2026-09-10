package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

public record StoredBlockIndex(
        Hash256 hash,
        BlockHeader header,
        long height,
        Hash256 previousBlockHash,
        BigInteger chainWork
) {

    public StoredBlockIndex {

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
        if (chainWork.bitLength() > 256) {
            throw new IllegalArgumentException(
                    "chainWork must fit into uint256"
            );
        }
    }
}