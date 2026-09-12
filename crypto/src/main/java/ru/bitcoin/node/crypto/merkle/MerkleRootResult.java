package ru.bitcoin.node.crypto.merkle;

import ru.bitcoin.node.common.types.Hash256;

public record MerkleRootResult(
        Hash256 root,
        boolean mutated
) {

    public MerkleRootResult {
        if (root == null) {
            throw new IllegalArgumentException(
                    "root must not be null"
            );
        }
    }
}