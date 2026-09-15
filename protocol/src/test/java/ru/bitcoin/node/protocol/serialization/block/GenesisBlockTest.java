package ru.bitcoin.node.protocol.serialization.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenesisBlockTest {

    private static final String GENESIS_BLOCK =
            "01000000" +
                    "0000000000000000000000000000000000000000000000000000000000000000" +
                    "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
                    "29ab5f49" +
                    "ffff001d" +
                    "1dac2b7c" +

                    "01" +

                    "01000000" +

                    "01" +

                    "0000000000000000000000000000000000000000000000000000000000000000" +
                    "ffffffff" +

                    "4d" +
                    "04ffff001d010445" +
                    "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73" +

                    "ffffffff" +

                    "01" +

                    "00f2052a01000000" +

                    "43" +
                    "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac" +

                    "00000000";

    @Test
    void factoryMatchesEntireKnownGenesisSerialization() {
        assertArrayEquals(HexUtils.decode(GENESIS_BLOCK), BlockSerializer.serialize(
                GenesisBlockFactory.create(NetworkParametersRegistry.mainnet())));
    }

    @Test
    void shouldParseRealBitcoinGenesisBlock() {

        byte[] raw =
                HexUtils.decode(GENESIS_BLOCK);

        Block block =
                BlockParser.parse(raw);

        assertEquals(
                "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
                block.hash().toDisplayHex()
        );

        assertEquals(
                1,
                block.transactions().size()
        );

        Transaction coinbase =
                block.transactions().getFirst();

        assertTrue(
                coinbase.isCoinbase()
        );

        assertEquals(
                5_000_000_000L,
                coinbase.outputs()
                        .getFirst()
                        .value()
        );
    }

    @Test
    void genesisTransactionShouldProduceHeaderMerkleRoot() {

        Block block =
                BlockParser.parse(
                        HexUtils.decode(GENESIS_BLOCK)
                );

        List<Hash256> transactionIds =
                block.transactions()
                        .stream()
                        .map(Transaction::txId)
                        .toList();

        Hash256 calculated =
                MerkleTree.calculateRoot(
                        transactionIds
                );

        assertEquals(
                block.header().merkleRoot(),
                calculated
        );

        assertEquals(
                "4a5e1e4baab89f3a32518a88c31bc87f" +
                        "618f76673e2cc77ab2127b7afdeda33b",
                calculated.toDisplayHex()
        );
    }

    @Test
    void genesisBlockShouldRoundTrip() {

        byte[] original =
                HexUtils.decode(GENESIS_BLOCK);

        Block parsed =
                BlockParser.parse(original);

        byte[] serialized =
                BlockSerializer.serialize(parsed);

        assertArrayEquals(
                original,
                serialized
        );
    }
}
