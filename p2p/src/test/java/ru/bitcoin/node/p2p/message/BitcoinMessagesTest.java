package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BitcoinMessagesTest {

    @Test
    void shouldCreateVerackMessage() {
        BitcoinMessage message =
                BitcoinMessages.verack();

        assertEquals(
                "verack",
                message.command()
        );

        assertEquals(
                0,
                message.payloadLength()
        );
    }

    @Test
    void shouldCreateVersionMessage() {
        VersionMessage version =
                new VersionMessage(
                        70017,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        1,
                        "/java-bitcoin-node:0.0.1/",
                        0,
                        true
                );

        BitcoinMessage message =
                BitcoinMessages.version(
                        version
                );

        assertEquals(
                "version",
                message.command()
        );
    }

    @Test
    void shouldRejectWrongCommandWhenDecodingVersion() {
        BitcoinMessage message =
                new BitcoinMessage(
                        "verack",
                        new byte[0]
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeVersion(
                        message
                )
        );
    }
    @Test
    void shouldCreateWtxidRelayMessage() {
        BitcoinMessage message =
                BitcoinMessages.wtxidRelay();

        assertEquals(
                "wtxidrelay",
                message.command()
        );

        assertEquals(
                0,
                message.payloadLength()
        );
    }

    @Test
    void shouldCreateSendAddrV2Message() {
        BitcoinMessage message =
                BitcoinMessages.sendAddrV2();

        assertEquals(
                "sendaddrv2",
                message.command()
        );

        assertEquals(
                0,
                message.payloadLength()
        );
    }
    @Test
    void shouldCreateGetHeadersMessage() {

        GetHeadersMessage getHeaders =
                new GetHeadersMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        List.of(
                                Hash256.fromDisplayHex(
                                        "0f9188f13cb7b2c71f2a335e3a4fc328"
                                                + "bf5beb436012afca590b1a11466e2206"
                                )
                        ),
                        new Hash256(
                                new byte[32]
                        )
                );

        BitcoinMessage message =
                BitcoinMessages.getHeaders(
                        getHeaders
                );

        assertEquals(
                "getheaders",
                message.command()
        );

        assertEquals(
                69,
                message.payloadLength()
        );
    }
    @Test
    void shouldDecodeHeadersMessage() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "headers",
                        new byte[]{0x00}
                );

        HeadersMessage headers =
                BitcoinMessages.decodeHeaders(
                        message
                );

        assertTrue(
                headers.isEmpty()
        );
    }
    @Test
    void shouldRejectWrongCommandWhenDecodingHeaders() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "block",
                        new byte[]{0x00}
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BitcoinMessages.decodeHeaders(
                                message
                        )
        );
    }
    @Test
    void shouldEncodeAndDecodePing() {

        long nonce =
                0x0807060504030201L;

        BitcoinMessage wire =
                BitcoinMessages.ping(
                        new PingMessage(
                                nonce
                        )
                );

        assertEquals(
                "ping",
                wire.command()
        );

        assertEquals(
                8,
                wire.payloadLength()
        );

        assertEquals(
                nonce,
                BitcoinMessages
                        .decodePing(wire)
                        .nonce()
        );
    }

    @Test
    void shouldEncodeAndDecodePong() {

        long nonce =
                0xfedcba9876543210L;

        BitcoinMessage wire =
                BitcoinMessages.pong(
                        new PongMessage(
                                nonce
                        )
                );

        assertEquals(
                "pong",
                wire.command()
        );

        assertEquals(
                nonce,
                BitcoinMessages
                        .decodePong(wire)
                        .nonce()
        );
    }
    @Test
    void createsAndDecodesGetDataMessage() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                );

        GetDataMessage original =
                new GetDataMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        BitcoinMessage wireMessage =
                BitcoinMessages.getData(
                        original
                );

        assertEquals(
                "getdata",
                wireMessage.command()
        );

        GetDataMessage decoded =
                BitcoinMessages.decodeGetData(
                        wireMessage
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                InventoryVector.MSG_WITNESS_BLOCK,
                decoded.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                hash,
                decoded.inventory()
                        .get(0)
                        .hash()
        );
    }

    @Test
    void decodeGetDataRejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "inv",
                        new byte[0]
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeGetData(
                        message
                )
        );
    }
    @Test
    void createsAndDecodesBlockMessage() {

        NetworkParameters parameters =
                NetworkParametersRegistry.forNetwork(
                        BitcoinNetwork.REGTEST
                );

        Block block =
                GenesisBlockFactory.create(
                        parameters
                );

        BitcoinMessage wireMessage =
                BitcoinMessages.block(
                        new BlockMessage(
                                block
                        )
                );

        assertEquals(
                "block",
                wireMessage.command()
        );

        BlockMessage decoded =
                BitcoinMessages.decodeBlock(
                        wireMessage
                );

        assertEquals(
                block.hash(),
                decoded.block().hash()
        );
    }

    @Test
    void decodeBlockRejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "headers",
                        new byte[0]
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeBlock(
                        message
                )
        );
    }

    @Test
    void createsAndDecodesInvMessage() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                );

        InvMessage original =
                new InvMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_BLOCK,
                                        hash
                                )
                        )
                );

        BitcoinMessage wireMessage =
                BitcoinMessages.inv(
                        original
                );

        assertEquals(
                "inv",
                wireMessage.command()
        );

        InvMessage decoded =
                BitcoinMessages.decodeInv(
                        wireMessage
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                InventoryVector.MSG_BLOCK,
                decoded.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                hash,
                decoded.inventory()
                        .get(0)
                        .hash()
        );
    }

    @Test
    void decodeInvRejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "getdata",
                        new byte[0]
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeInv(
                        message
                )
        );
    }
}