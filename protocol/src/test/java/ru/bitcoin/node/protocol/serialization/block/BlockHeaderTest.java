package ru.bitcoin.node.protocol.serialization.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.serialization.BlockHeaderParser;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockHeaderTest {

    @Test
    void shouldSerializeBitcoinGenesisHeader() {

        BlockHeader header =
                bitcoinGenesisHeader();

        byte[] serialized =
                BlockHeaderSerializer.serialize(header);

        assertEquals(
                80,
                serialized.length
        );

        assertEquals(
                "01000000" +
                        "00000000000000000000000000000000" +
                        "00000000000000000000000000000000" +
                        "3ba3edfd7a7b12b27ac72c3e67768f61" +
                        "7fc81bc3888a51323a9fb8aa4b1e5e4a" +
                        "29ab5f49" +
                        "ffff001d" +
                        "1dac2b7c",
                HexUtils.encode(serialized)
        );
    }

    @Test
    void shouldCalculateBitcoinGenesisBlockHash() {

        BlockHeader header =
                bitcoinGenesisHeader();

        Hash256 hash =
                header.hash();

        assertEquals(
                "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
                hash.toDisplayHex()
        );
    }

    private static BlockHeader bitcoinGenesisHeader() {

        Hash256 previousBlockHash =
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                );

        Hash256 merkleRoot =
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                );

        return new BlockHeader(
                1,
                previousBlockHash,
                merkleRoot,
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
    //Round-trip test заголовка
    @Test
    void shouldParseGenesisHeader() {

        BlockHeader original =
                bitcoinGenesisHeader();

        byte[] serialized =
                BlockHeaderSerializer.serialize(
                        original
                );

        BlockHeader parsed =
                BlockHeaderParser.parse(
                        serialized
                );

        assertEquals(
                original.version(),
                parsed.version()
        );

        assertEquals(
                original.previousBlockHash(),
                parsed.previousBlockHash()
        );

        assertEquals(
                original.merkleRoot(),
                parsed.merkleRoot()
        );

        assertEquals(
                original.timestamp(),
                parsed.timestamp()
        );

        assertEquals(
                original.bits(),
                parsed.bits()
        );

        assertEquals(
                original.nonce(),
                parsed.nonce()
        );

        assertEquals(
                original.hash(),
                parsed.hash()
        );
    }
}
