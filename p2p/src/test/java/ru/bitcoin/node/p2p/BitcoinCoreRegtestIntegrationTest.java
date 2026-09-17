package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcoinCoreRegtestIntegrationTest {

    private static final String CORE_HOST =
            "127.0.0.1";

    private static final int CORE_P2P_PORT =
            18444;

    private static final Hash256 REGTEST_GENESIS =
            Hash256.fromDisplayHex(
                    "0f9188f13cb7b2c71f2a335e3a4fc328"
                            + "bf5beb436012afca590b1a11466e2206"
            );

    private static final Hash256 EXPECTED_TIP =
            Hash256.fromDisplayHex(
                    "58fb5d854840e3d20f48f8226b56c2a6"
                            + "d6cba54e366a896de7d179fe70c34668"
            );

    @Test
    @EnabledIfSystemProperty(
            named = "bitcoin.core.integration",
            matches = "true"
    )
    void shouldHandshakeAndDownloadHeadersFromBitcoinCoreRegtest()
            throws Exception {

        try (PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest(),
                             5_000,
                             10_000
                     );

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true
                     )) {

            peer.connect(
                    CORE_HOST,
                    CORE_P2P_PORT
            );

            peer.handshake();

            assertTrue(
                    peer.isReady()
            );

            assertEquals(
                    PeerState.READY,
                    peer.state()
            );

            /*
             * Test Java -> Bitcoin Core ping/pong.
             */
            long pingNonce =
                    0x0102030405060708L;

            connection.send(
                    BitcoinMessages.ping(
                            new PingMessage(
                                    pingNonce
                            )
                    )
            );

            boolean pongReceived =
                    false;

            while (!pongReceived) {

                Optional<BitcoinMessage> optional =
                        connection.receive();

                if (optional.isEmpty()) {
                    throw new IllegalStateException(
                            "Bitcoin Core disconnected "
                                    + "before sending pong"
                    );
                }

                BitcoinMessage message =
                        optional.get();

                System.out.println(
                        "Received while waiting for pong: "
                                + message.command()
                );

                if ("pong".equals(
                        message.command()
                )) {

                    PongMessage pong =
                            BitcoinMessages.decodePong(
                                    message
                            );

                    assertEquals(
                            pingNonce,
                            pong.nonce()
                    );

                    pongReceived =
                            true;

                    System.out.println(
                            "Bitcoin Core pong nonce: "
                                    + Long.toUnsignedString(
                                    pong.nonce()
                            )
                    );

                    continue;
                }

                /*
                 * Core may independently ping us while
                 * we are waiting for our pong.
                 */
                peer.handleMessage(
                        message
                );
            }

            VersionMessage remote =
                    peer.remoteVersion();

            System.out.println(
                    "Bitcoin Core protocol version: "
                            + remote.version()
            );

            System.out.println(
                    "Bitcoin Core services: "
                            + Long.toUnsignedString(
                            remote.services()
                    )
            );

            System.out.println(
                    "Bitcoin Core user agent: "
                            + remote.userAgent()
            );

            System.out.println(
                    "Bitcoin Core start height: "
                            + remote.startHeight()
            );

            System.out.println(
                    "Remote WTXID relay: "
                            + peer.remoteWtxidRelay()
            );

            System.out.println(
                    "Remote ADDRv2: "
                            + peer.remoteWantsAddrV2()
            );

            /*
             * Ask Bitcoin Core for every header
             * after the regtest genesis block.
             */
            GetHeadersMessage getHeaders =
                    new GetHeadersMessage(
                            remote.version(),
                            List.of(
                                    REGTEST_GENESIS
                            ),
                            new Hash256(
                                    new byte[Hash256.LENGTH]
                            )
                    );

            connection.send(
                    BitcoinMessages.getHeaders(
                            getHeaders
                    )
            );

            BitcoinMessage headersWireMessage =
                    receiveHeaders(
                            connection,
                            peer
                    );

            HeadersMessage headers =
                    BitcoinMessages.decodeHeaders(
                            headersWireMessage
                    );

            System.out.println(
                    "Received headers: "
                            + headers.size()
            );

            assertEquals(
                    3,
                    headers.size()
            );

            List<BlockHeader> blockHeaders =
                    headers.headers();

            /*
             * Header #1 must extend genesis.
             */
            assertEquals(
                    REGTEST_GENESIS,
                    blockHeaders
                            .get(0)
                            .previousBlockHash()
            );

            /*
             * Header #2 must extend header #1.
             */
            assertEquals(
                    blockHeaders
                            .get(0)
                            .hash(),
                    blockHeaders
                            .get(1)
                            .previousBlockHash()
            );

            /*
             * Header #3 must extend header #2.
             */
            assertEquals(
                    blockHeaders
                            .get(1)
                            .hash(),
                    blockHeaders
                            .get(2)
                            .previousBlockHash()
            );

            /*
             * The last downloaded header must be
             * the current Bitcoin Core regtest tip.
             */
            assertEquals(
                    EXPECTED_TIP,
                    blockHeaders
                            .get(2)
                            .hash()
            );

            for (int i = 0;
                 i < blockHeaders.size();
                 i++) {

                System.out.println(
                        "Header height "
                                + (i + 1)
                                + ": "
                                + blockHeaders
                                .get(i)
                                .hash()
                                .toDisplayHex()
                );
            }

            System.out.println(
                    "Header chain tip: "
                            + blockHeaders
                            .get(2)
                            .hash()
                            .toDisplayHex()
            );
            /*
             * Download the complete first block.
             *
             * Request witness serialization so that blocks containing
             * SegWit transactions are not silently downgraded to
             * legacy transaction serialization.
             */
            Hash256 requestedBlockHash =
                    blockHeaders
                            .get(0)
                            .hash();

            GetDataMessage getData =
                    new GetDataMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_WITNESS_BLOCK,
                                            requestedBlockHash
                                    )
                            )
                    );

            connection.send(
                    BitcoinMessages.getData(
                            getData
                    )
            );

            BitcoinMessage blockWireMessage =
                    receiveBlock(
                            connection,
                            peer
                    );

            BlockMessage blockMessage =
                    BitcoinMessages.decodeBlock(
                            blockWireMessage
                    );

            assertEquals(
                    requestedBlockHash,
                    blockMessage
                            .block()
                            .hash()
            );

            assertEquals(
                    REGTEST_GENESIS,
                    blockMessage
                            .block()
                            .header()
                            .previousBlockHash()
            );

            assertTrue(
                    !blockMessage
                            .block()
                            .transactions()
                            .isEmpty()
            );

            System.out.println(
                    "Downloaded block: "
                            + blockMessage
                            .block()
                            .hash()
                            .toDisplayHex()
            );

            System.out.println(
                    "Downloaded block transactions: "
                            + blockMessage
                            .block()
                            .transactions()
                            .size()
            );
        }
    }

    private static BitcoinMessage receiveHeaders(
            PeerConnection connection,
            Peer peer
    ) throws Exception {

        while (true) {

            Optional<BitcoinMessage> received =
                    connection.receive();

            if (received.isEmpty()) {
                throw new IllegalStateException(
                        "Bitcoin Core closed connection "
                                + "before sending headers"
                );
            }

            BitcoinMessage message =
                    received.get();

            System.out.println(
                    "Received P2P message: "
                            + message.command()
            );

            if ("headers".equals(
                    message.command()
            )) {
                return message;
            }
            peer.handleMessage(
                    message
            );
        }
    }
    private static BitcoinMessage receiveBlock(
            PeerConnection connection,
            Peer peer
    ) throws Exception {

        while (true) {

            Optional<BitcoinMessage> received =
                    connection.receive();

            if (received.isEmpty()) {
                throw new IllegalStateException(
                        "Bitcoin Core closed connection "
                                + "before sending block"
                );
            }

            BitcoinMessage message =
                    received.get();

            System.out.println(
                    "Received while waiting for block: "
                            + message.command()
            );

            if ("block".equals(
                    message.command()
            )) {
                return message;
            }

            /*
             * Core may send ping or other asynchronous
             * post-handshake messages while the block
             * request is in flight.
             */
            peer.handleMessage(
                    message
            );
        }
    }
}