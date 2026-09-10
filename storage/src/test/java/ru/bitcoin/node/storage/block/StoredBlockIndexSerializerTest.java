package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class StoredBlockIndexSerializerTest {

    @Test
    void shouldSerializeAndDeserializeStoredBlockIndex() {

        BlockHeader header =
                genesisHeader();

        StoredBlockIndex original =
                new StoredBlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        new BigInteger(
                                "4295032833"
                        )
                );

        byte[] bytes =
                StoredBlockIndexSerializer.serialize(
                        original
                );

        StoredBlockIndex restored =
                StoredBlockIndexSerializer.deserialize(
                        bytes
                );

        assertEquals(
                StoredBlockIndexSerializer.SERIALIZED_SIZE,
                bytes.length
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

    @Test
    void shouldSerializeMaximumUint256ChainWork() {

        BlockHeader header =
                genesisHeader();

        BigInteger maximum =
                BigInteger.ONE
                        .shiftLeft(256)
                        .subtract(
                                BigInteger.ONE
                        );

        StoredBlockIndex original =
                new StoredBlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        maximum
                );

        byte[] bytes =
                StoredBlockIndexSerializer.serialize(
                        original
                );

        StoredBlockIndex restored =
                StoredBlockIndexSerializer.deserialize(
                        bytes
                );

        assertEquals(
                maximum,
                restored.chainWork()
        );
    }

    @Test
    void shouldRejectChainWorkLargerThanUint256() {

        BlockHeader header =
                genesisHeader();

        BigInteger tooLarge =
                BigInteger.ONE.shiftLeft(256);

        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredBlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        tooLarge
                )
        );
    }

    @Test
    void shouldRejectInvalidSerializedSize() {

        assertThrows(
                IllegalArgumentException.class,
                () -> StoredBlockIndexSerializer.deserialize(
                        new byte[10]
                )
        );
    }

    @Test
    void shouldRejectUnsupportedFormatVersion() {

        BlockHeader header =
                genesisHeader();

        StoredBlockIndex index =
                new StoredBlockIndex(
                        header.hash(),
                        header,
                        0,
                        header.previousBlockHash(),
                        BigInteger.ONE
                );

        byte[] bytes =
                StoredBlockIndexSerializer.serialize(
                        index
                );

        bytes[0] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () -> StoredBlockIndexSerializer.deserialize(
                        bytes
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