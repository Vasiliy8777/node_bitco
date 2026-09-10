package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StoredBlockIndexTest {

    @Test
    void shouldRejectNegativeHeight() {

        BlockHeader header =
                header();

        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredBlockIndex(
                        header.hash(),
                        header,
                        -1,
                        header.previousBlockHash(),
                        BigInteger.ONE
                )
        );
    }

    @Test
    void shouldRejectNegativeChainWork() {

        BlockHeader header =
                header();

        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredBlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        BigInteger.valueOf(-1)
                )
        );
    }

    private static BlockHeader header() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
}