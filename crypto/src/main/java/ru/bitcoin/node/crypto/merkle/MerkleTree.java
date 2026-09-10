package ru.bitcoin.node.crypto.merkle;

import ru.bitcoin.node.common.bytes.ByteUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.Hash256Digest;

import java.util.ArrayList;
import java.util.List;

public final class MerkleTree {

    private MerkleTree() {
    }

    public static Hash256 calculateRoot(
            List<Hash256> hashes
    ) {
        if (hashes == null) {
            throw new IllegalArgumentException(
                    "hashes must not be null"
            );
        }

        if (hashes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot calculate Merkle root of empty list"
            );
        }

        if (hashes.size() == 1) {
            return hashes.getFirst();
        }

        List<Hash256> current =
                new ArrayList<>(hashes);

        while (current.size() > 1) {

            List<Hash256> next =
                    new ArrayList<>(
                            (current.size() + 1) / 2
                    );

            for (int i = 0; i < current.size(); i += 2) {

                Hash256 left = current.get(i);

                Hash256 right =
                        (i + 1 < current.size())
                                ? current.get(i + 1)
                                : left;

                byte[] combined =
                        ByteUtils.concat(
                                left.bytes(),
                                right.bytes()
                        );

                Hash256 parent =
                        Hash256Digest.hash(combined);

                next.add(parent);
            }

            current = next;
        }

        return current.getFirst();
    }
}
