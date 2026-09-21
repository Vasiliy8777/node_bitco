package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OutboundPeerManagerTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.mainnet();

    @Test
    void shouldFailOverToNextAddressAndRegisterReadyPeer()
            throws Exception {

        try (ServerSocket successfulServer =
                     new ServerSocket(0);

             ServerSocket unusedServer =
                     new ServerSocket(0)) {

            int failedPort =
                    unusedServer.getLocalPort();

            /*
             * Reserve a free port and then close it so that
             * the first outbound connection is refused.
             */
            unusedServer.close();

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSuccessfulPeer(
                                    successfulServer
                            )
                    );

            PeerAddress failedAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            failedPort,
                            0L
                    );

            PeerAddress successfulAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            successfulServer.getLocalPort(),
                            0L
                    );

            PeerAddressManager addressManager =
                    new PeerAddressManager();

            Instant discoveredAt =
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    );

            addressManager.add(
                    failedAddress,
                    discoveredAt
            );

            addressManager.add(
                    successfulAddress,
                    discoveredAt
            );

            PeerManager peerManager =
                    new PeerManager();

            AtomicInteger clockCalls =
                    new AtomicInteger();

            Instant base =
                    Instant.ofEpochSecond(
                            1_700_000_100L
                    );

            OutboundPeerManager outbound =
                    new OutboundPeerManager(
                            new BitcoinClient(
                                    PARAMETERS
                            ),
                            peerManager,
                            addressManager,
                            new ru.bitcoin.node.p2p.address.OutboundPeerSelector(
                                    addressManager
                            ),
                            () -> base.plusSeconds(
                                    clockCalls.getAndIncrement()
                            )
                    );

            try {

                Peer peer =
                        outbound.connectOne(
                                100
                        );

                assertTrue(
                        peer.isReady()
                );

                assertEquals(
                        1,
                        peerManager.size()
                );

                assertSame(
                        peer,
                        peerManager.readyPeers()
                                .get(0)
                );

                var failedKnown =
                        addressManager.find(
                                failedAddress
                        ).orElseThrow();

                assertEquals(
                        1,
                        failedKnown.attempts()
                );

                assertTrue(
                        failedKnown.lastAttempt()
                                .isPresent()
                );

                assertTrue(
                        failedKnown.lastSuccess()
                                .isEmpty()
                );

                var successfulKnown =
                        addressManager.find(
                                successfulAddress
                        ).orElseThrow();

                assertEquals(
                        0,
                        successfulKnown.attempts()
                );

                assertTrue(
                        successfulKnown.lastAttempt()
                                .isPresent()
                );

                assertTrue(
                        successfulKnown.lastSuccess()
                                .isPresent()
                );

            } finally {

                peerManager.close();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailWhenNoKnownAddressesExist() {

        PeerAddressManager addressManager =
                new PeerAddressManager();

        PeerManager peerManager =
                new PeerManager();

        OutboundPeerManager outbound =
                new OutboundPeerManager(
                        new BitcoinClient(
                                PARAMETERS
                        ),
                        peerManager,
                        addressManager
                );

        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> outbound.connectOne(
                                0
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "No known peer addresses"
                        )
        );

        assertTrue(
                peerManager.isEmpty()
        );
    }

    @Test
    void shouldRecordFailureWhenAllAddressesFail()
            throws Exception {

        PeerAddressManager addressManager =
                new PeerAddressManager();

        PeerAddress address;

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    );
        }

        addressManager.add(
                address,
                Instant.ofEpochSecond(
                        1_700_000_000L
                )
        );

        PeerManager peerManager =
                new PeerManager();

        OutboundPeerManager outbound =
                new OutboundPeerManager(
                        new BitcoinClient(
                                PARAMETERS
                        ),
                        peerManager,
                        addressManager
                );

        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> outbound.connectOne(
                                0
                        )
                );

        assertEquals(
                1,
                exception.getSuppressed().length
        );

        assertTrue(
                peerManager.isEmpty()
        );

        var known =
                addressManager.find(
                        address
                ).orElseThrow();

        assertEquals(
                1,
                known.attempts()
        );

        assertTrue(
                known.lastAttempt()
                        .isPresent()
        );

        assertTrue(
                known.lastSuccess()
                        .isEmpty()
        );
    }

    private static void runSuccessfulPeer(
            ServerSocket serverSocket
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BitcoinMessage version =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "version",
                    version.command()
            );

            VersionMessage decoded =
                    BitcoinMessages.decodeVersion(
                            version
                    );

            assertEquals(
                    100,
                    decoded.startHeight()
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            0x123456789ABCDEFL,
                            "/outbound-peer-manager-test/",
                            321,
                            true
                    );

            output.write(
                    encoder.encode(
                            BitcoinMessages.version(
                                    remoteVersion
                            )
                    )
            );

            output.flush();

            assertEquals(
                    "wtxidrelay",
                    reader.read(input)
                            .orElseThrow()
                            .command()
            );

            assertEquals(
                    "sendaddrv2",
                    reader.read(input)
                            .orElseThrow()
                            .command()
            );

            assertEquals(
                    "verack",
                    reader.read(input)
                            .orElseThrow()
                            .command()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.wtxidRelay()
                    )
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.sendAddrV2()
                    )
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.verack()
                    )
            );

            output.flush();

            /*
             * Wait until PeerManager.close() closes
             * the successfully registered peer.
             */
            assertEquals(
                    -1,
                    input.read()
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }
}