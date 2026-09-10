package ru.bitcoin.node.merkle;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.ByteUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MerkleTreeTest {

    @Test
    void singleHashShouldBeRoot() {

        Hash256 hash =
                Hash256Digest.hash(
                        "tx1".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 root =
                MerkleTree.calculateRoot(
                        List.of(hash)
                );

        assertEquals(hash, root);
    }

    @Test
    void shouldCalculateRootForTwoHashes() {

        Hash256 first =
                Hash256Digest.hash(
                        "tx1".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 second =
                Hash256Digest.hash(
                        "tx2".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 expected =
                Hash256Digest.hash(
                        ByteUtils.concat(
                                first.bytes(),
                                second.bytes()
                        )
                );

        Hash256 actual =
                MerkleTree.calculateRoot(
                        List.of(first, second)
                );

        assertEquals(expected, actual);
    }

    @Test
    void shouldDuplicateLastHashForOddCount() {

        Hash256 first =
                Hash256Digest.hash(
                        "tx1".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 second =
                Hash256Digest.hash(
                        "tx2".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 third =
                Hash256Digest.hash(
                        "tx3".getBytes(StandardCharsets.UTF_8)
                );

        Hash256 left =
                Hash256Digest.hash(
                        ByteUtils.concat(
                                first.bytes(),
                                second.bytes()
                        )
                );

        Hash256 right =
                Hash256Digest.hash(
                        ByteUtils.concat(
                                third.bytes(),
                                third.bytes()
                        )
                );

        Hash256 expected =
                Hash256Digest.hash(
                        ByteUtils.concat(
                                left.bytes(),
                                right.bytes()
                        )
                );

        Hash256 actual =
                MerkleTree.calculateRoot(
                        List.of(
                                first,
                                second,
                                third
                        )
                );

        assertEquals(expected, actual);
    }
}
