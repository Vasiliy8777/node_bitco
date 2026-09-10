package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.block.StoredBlockIndex;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockIndexStorageMapperTest {

    @Test
    void shouldConvertBlockIndexToStoredAndBack() {

        BlockHeader header =
                new BlockHeader(
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

        BlockIndex original =
                new BlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        BigInteger.valueOf(123456)
                );

        StoredBlockIndex stored =
                BlockIndexStorageMapper.toStored(
                        original
                );

        BlockIndex restored =
                BlockIndexStorageMapper.fromStored(
                        stored
                );

        assertEquals(
                original.hash(),
                restored.hash()
        );

        assertEquals(
                original.header(),
                restored.header()
        );

        assertEquals(
                original.height(),
                restored.height()
        );

        assertEquals(
                original.previousBlockHash(),
                restored.previousBlockHash()
        );

        assertEquals(
                original.chainWork(),
                restored.chainWork()
        );
    }
}