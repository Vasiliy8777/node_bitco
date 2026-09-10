package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockIndexTest {

    @Test
    void shouldCreateGenesisBlockIndex() {

        BlockHeader header = genesisHeader();

        BigInteger work =
                ChainWork.blockWork(
                        header.bits().value()
                );

        BlockIndex index =
                new BlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        work
                );

        assertEquals(
                0,
                index.height()
        );

        assertEquals(
                header.hash(),
                index.hash()
        );

        assertEquals(
                header.previousBlockHash(),
                index.previousBlockHash()
        );

        assertEquals(
                work,
                index.chainWork()
        );
    }

    @Test
    void shouldRejectNegativeHeight() {

        BlockHeader header = genesisHeader();

        assertThrows(
                IllegalArgumentException.class,
                () -> new BlockIndex(
                        header.hash(),
                        header,
                        -1,
                        header.previousBlockHash(),
                        BigInteger.ONE
                )
        );
    }

    private static BlockHeader genesisHeader() {

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
