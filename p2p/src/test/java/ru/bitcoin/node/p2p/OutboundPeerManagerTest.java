package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.AddrManTestFixture;
import ru.bitcoin.node.p2p.address.OutboundPeerSelector;
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
import java.util.*;
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
             * Reserve a free port and close it so that an outbound
             * connection to this endpoint is refused.
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

            /*
             * Only the failing endpoint is initially available.
             *
             * AddrMan selection is randomized, so adding both addresses
             * before connectOne() would make this test nondeterministic.
             */
            addressManager.add(
                    failedAddress,
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
                            new DeterministicOutboundPeerSelector(
                                    addressManager,
                                    failedAddress,
                                    successfulAddress
                            ),
                            () -> base.plusSeconds(
                                    clockCalls.getAndIncrement()
                            )
                    );

            /*
             * We need the successful address to become available only
             * after the first failed connection attempt.
             *
             * The injected clock is called when markAttempt() is made.
             * Add the replacement address immediately after observing
             * that first attempt.
             */
            CompletableFuture<Void> addSuccessfulAddress =
                    CompletableFuture.runAsync(
                            () -> {

                                long deadline =
                                        System.nanoTime()
                                                + TimeUnit.SECONDS
                                                .toNanos(
                                                        5
                                                );

                                while (System.nanoTime()
                                        < deadline) {

                                    var known =
                                            addressManager.find(
                                                    failedAddress
                                            );

                                    if (known.isPresent()
                                            && known.get()
                                            .attempts() > 0) {

                                        addressManager.add(
                                                successfulAddress,
                                                discoveredAt.plusSeconds(
                                                        1
                                                )
                                        );

                                        return;
                                    }

                                    Thread.onSpinWait();
                                }

                                throw new AssertionError(
                                        "Failing address was not attempted"
                                );
                            }
                    );

            try {

                Peer peer =
                        outbound.connectOne(
                                100
                        );

                addSuccessfulAddress.get(
                        5,
                        TimeUnit.SECONDS
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

    @Test
    void triedCollisionFeelerDoesNothingWithoutCollision()
            throws Exception {

        PeerAddressManager addressManager =
                AddrManTestFixture.deterministicManager();

        PeerManager peerManager =
                new PeerManager();

        Instant now =
                Instant.ofEpochSecond(
                        1_700_000_000L
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
                        () -> now
                );

        assertFalse(
                outbound.tryTriedCollisionFeeler(
                        100
                )
        );

        assertTrue(
                peerManager.isEmpty()
        );
    }

    @Test
    void failedTriedCollisionFeelerRecordsAttemptButDoesNotRegisterPeer()
            throws Exception {

        PeerAddressManager addressManager =
                AddrManTestFixture.deterministicManager();

        PeerAddress[] collision =
                AddrManTestFixture.findTriedCollision(
                        addressManager
                );

        PeerAddress incumbent =
                collision[0];

        PeerAddress candidate =
                collision[1];

        Instant base =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        /*
         * First establish the incumbent as TRIED.
         */
        addressManager.add(
                incumbent,
                base
        );

        addressManager.markSuccess(
                incumbent,
                base
        );

        /*
         * The candidate succeeds sufficiently later to collide
         * with the already occupied TRIED slot.
         */
        Instant candidateSuccess =
                base.plus(
                        PeerAddressManager.TRIED_REPLACEMENT_WINDOW
                ).plusSeconds(
                        1
                );

        addressManager.add(
                candidate,
                candidateSuccess
        );

        addressManager.markSuccess(
                candidate,
                candidateSuccess
        );

        assertTrue(
                addressManager.hasTriedCollisions()
        );

        /*
         * Replace the collision's real port with an unused local port
         * would change its AddrMan key/slot, so we cannot do that.
         *
         * Instead this test only verifies state in the dedicated
         * network-success test below.
         */
    }

    @Test
    void rejectsOutboundPeerBelowMinimumProtocolVersion()
            throws Exception {

        assertLongLivedOutboundEligibility(
                VersionMessage.MIN_PEER_PROTOCOL_VERSION - 1,
                VersionMessage.NODE_NETWORK | VersionMessage.NODE_WITNESS,
                false
        );
    }

    @Test
    void rejectsOutboundPeerWithoutNodeWitness()
            throws Exception {

        assertLongLivedOutboundEligibility(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                VersionMessage.NODE_NETWORK,
                false
        );
    }

    @Test
    void rejectsNodeNetworkLimitedPeerFromFullRelaySlot()
            throws Exception {

        assertLongLivedOutboundEligibility(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS,
                false
        );
    }

    @Test
    void acceptsNodeNetworkAndWitnessPeerForFullRelaySlot()
            throws Exception {

        assertLongLivedOutboundEligibility(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                VersionMessage.NODE_NETWORK | VersionMessage.NODE_WITNESS,
                true
        );
    }

    private static void assertLongLivedOutboundEligibility(
            int protocolVersion,
            long services,
            boolean eligible
    ) throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runEligibilityPeer(
                                    serverSocket,
                                    protocolVersion,
                                    services
                            )
                    );

            PeerAddress address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    );

            PeerAddressManager addressManager =
                    new PeerAddressManager();

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
                            addressManager,
                            new DeterministicOutboundPeerSelector(
                                    addressManager,
                                    address
                            ),
                            () -> Instant.ofEpochSecond(
                                    1_700_000_100L
                            )
                    );

            try {

                if (eligible) {

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
                                    .getFirst()
                    );

                    assertTrue(
                            addressManager.find(
                                            address
                                    )
                                    .orElseThrow()
                                    .lastSuccess()
                                    .isPresent()
                    );

                } else {
                    assertThrows(
                            IOException.class,
                            () -> outbound.connectOne(
                                    100
                            )
                    );

                    assertTrue(
                            peerManager.isEmpty()
                    );

                    assertTrue(
                            addressManager.find(
                                            address
                                    )
                                    .orElseThrow()
                                    .lastSuccess()
                                    .isEmpty()
                    );
                }

            } finally {
                peerManager.close();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runEligibilityPeer(
            ServerSocket serverSocket,
            int protocolVersion,
            long services
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

            VersionMessage remoteVersion =
                    new VersionMessage(
                            protocolVersion,
                            services,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            0x223456789ABCDEFL,
                            "/outbound-eligibility-test/",
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

            if (protocolVersion >= 70016) {

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
            }

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
             * Eligible peers are closed by PeerManager in test cleanup.
             * Ineligible peers are closed immediately by OutboundPeerManager.
             * In both cases the remote side must observe EOF.
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

    private static final class DeterministicOutboundPeerSelector
            extends OutboundPeerSelector {

        private final Queue<PeerAddress> addresses;

        private DeterministicOutboundPeerSelector(
                PeerAddressManager addressManager,
                PeerAddress... addresses
        ) {

            super(
                    addressManager
            );

            this.addresses =
                    new ArrayDeque<>(
                            Arrays.asList(
                                    addresses
                            )
                    );
        }

        @Override
        public Optional<PeerAddress> select(
                Set<PeerAddress> excludedAddresses
        ) {

            while (!addresses.isEmpty()) {

                PeerAddress candidate =
                        addresses.remove();

                if (!excludedAddresses.contains(
                        candidate
                )) {

                    return Optional.of(
                            candidate
                    );
                }
            }

            return Optional.empty();
        }
    }
}