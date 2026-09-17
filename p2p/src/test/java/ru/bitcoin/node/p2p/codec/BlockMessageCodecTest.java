package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BlockMessage;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockMessageCodecTest {

    @Test
    void roundTripsRegtestGenesisBlock() {

        NetworkParameters parameters =
                NetworkParametersRegistry.forNetwork(
                        BitcoinNetwork.REGTEST
                );

        Block original =
                GenesisBlockFactory.create(
                        parameters
                );

        BlockMessage message =
                new BlockMessage(
                        original
                );

        byte[] encoded =
                BlockMessageCodec.encode(
                        message
                );

        BlockMessage decoded =
                BlockMessageCodec.decode(
                        encoded
                );

        assertEquals(
                original.hash(),
                decoded.block().hash()
        );

        assertEquals(
                original.header(),
                decoded.block().header()
        );

        assertEquals(
                original.transactions().size(),
                decoded.block()
                        .transactions()
                        .size()
        );

        assertArrayEquals(
                encoded,
                BlockMessageCodec.encode(
                        decoded
                )
        );
    }
}