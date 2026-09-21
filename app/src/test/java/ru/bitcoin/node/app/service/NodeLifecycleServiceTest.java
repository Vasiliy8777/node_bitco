package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.config.NetworkConfiguration;
import ru.bitcoin.node.app.config.NodeConfiguration;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexFactory;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NodeLifecycleServiceTest {

    private static final long BITS =
            0x207fffffL;

    private static final long REWARD =
            5_000_000_000L;

    @TempDir
    Path directory;

    @Test
    void shouldFailStartupWhenNoOutboundPeerCanBeDiscovered() {

        try (var context =
                     new AnnotationConfigApplicationContext()) {

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory.toString(),
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            PeerManager peerManager =
                    context.getBean(
                            PeerManager.class
                    );

            assertEquals(
                    NodeLifecycleState.NEW,
                    lifecycle.state()
            );

            IOException exception =
                    assertThrows(
                            IOException.class,
                            lifecycle::start
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    "Unable to synchronize headers"
                            )
            );

            assertEquals(
                    1,
                    exception.getSuppressed().length
            );

            Throwable connectFailure =
                    exception.getSuppressed()[0];

            assertInstanceOf(
                    IOException.class,
                    connectFailure
            );

            assertTrue(
                    connectFailure.getMessage()
                            .contains(
                                    "No known peer addresses available"
                            )
            );

            assertEquals(
                    NodeLifecycleState.FAILED,
                    lifecycle.state()
            );

            assertTrue(
                    lifecycle.failure()
                            .isPresent()
            );

            assertSame(
                    exception,
                    lifecycle.failure()
                            .orElseThrow()
            );

            assertFalse(
                    lifecycle.isRunning()
            );

            assertTrue(
                    peerManager.isEmpty()
            );
        }
    }

    @Test
    void shouldRejectSecondStartAttemptAfterFailure() {

        try (var context =
                     new AnnotationConfigApplicationContext()) {

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory.toString(),
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            assertThrows(
                    IOException.class,
                    lifecycle::start
            );

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            lifecycle::start
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    "FAILED"
                            )
            );
        }
    }

    @Test
    void shouldCompleteInitialBlockDownloadAndEnterRunningState()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        Block genesisBlock =
                GenesisBlockFactory.create(
                        parameters
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        1
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             var context =
                     new AnnotationConfigApplicationContext()) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runLifecyclePeer(
                                    serverSocket,
                                    parameters,
                                    block1
                            )
                    );

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-e2e-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory
                                                    .resolve("e2e")
                                                    .toString(),
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            PeerAddressManager addressManager =
                    context.getBean(
                            PeerAddressManager.class
                    );

            PeerAddress peerAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    );

            addressManager.add(
                    peerAddress,
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            NodeValidationService validationService =
                    context.getBean(
                            NodeValidationService.class
                    );

            NodeSyncInfrastructure syncInfrastructure =
                    context.getBean(
                            NodeSyncInfrastructure.class
                    );

            PeerManager peerManager =
                    context.getBean(
                            PeerManager.class
                    );

            assertEquals(
                    NodeLifecycleState.NEW,
                    lifecycle.state()
            );

            assertEquals(
                    genesis.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            Thread lifecycleThread =
                    startLifecycle(
                            lifecycle
                    );

            try {

                awaitRunning(
                        lifecycle
                );

                assertTrue(
                        lifecycleThread.isAlive()
                );

                assertTrue(
                        lifecycle.failure()
                                .isEmpty()
                );

                assertEquals(
                        block1.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        1L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block1.hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        validationService
                                .activeTip()
                                .hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        1,
                        peerManager.readyPeers()
                                .size()
                );

            } finally {

                closeAndAwaitLifecycle(
                        lifecycle,
                        lifecycleThread
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );

            assertFalse(
                    lifecycleThread.isAlive()
            );

            assertEquals(
                    NodeLifecycleState.STOPPED,
                    lifecycle.state()
            );

            assertFalse(
                    lifecycle.isRunning()
            );

            assertTrue(
                    lifecycle.failure()
                            .isEmpty()
            );

            /*
             * Synchronization result must remain committed
             * after lifecycle shutdown.
             */
            assertEquals(
                    block1.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            assertEquals(
                    1L,
                    validationService
                            .activeTip()
                            .height()
            );

            assertEquals(
                    block1.hash(),
                    syncInfrastructure
                            .headerChainState()
                            .bestHeaderTip()
                            .hash()
            );

            assertEquals(
                    validationService
                            .activeTip()
                            .hash(),
                    syncInfrastructure
                            .headerChainState()
                            .bestHeaderTip()
                            .hash()
            );

            /*
             * Shutdown closes and removes every managed peer.
             */
            assertTrue(
                    peerManager.isEmpty()
            );
        }
    }

    private static void runLifecyclePeer(
            ServerSocket serverSocket,
            NetworkParameters parameters,
            Block block
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
                                    parameters
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            parameters
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            BitcoinMessage firstGetHeaders =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    firstGetHeaders.command()
            );

            GetHeadersMessage firstRequest =
                    GetHeadersMessageCodec.decode(
                            firstGetHeaders.payload()
                    );

            assertFalse(
                    firstRequest.locatorHashes()
                            .isEmpty()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.headers(
                                    new HeadersMessage(
                                            List.of(
                                                    block.header()
                                            )
                                    )
                            )
                    )
            );

            output.flush();

            BitcoinMessage secondGetHeaders =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    secondGetHeaders.command()
            );

            GetHeadersMessage secondRequest =
                    GetHeadersMessageCodec.decode(
                            secondGetHeaders.payload()
                    );

            assertEquals(
                    block.hash(),
                    secondRequest.locatorHashes()
                            .get(0)
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.headers(
                                    new HeadersMessage(
                                            List.of()
                                    )
                            )
                    )
            );

            output.flush();

            BitcoinMessage getDataWire =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getdata",
                    getDataWire.command()
            );

            GetDataMessage getData =
                    BitcoinMessages.decodeGetData(
                            getDataWire
                    );

            assertEquals(
                    1,
                    getData.size()
            );

            InventoryVector requested =
                    getData.inventory()
                            .get(0);

            assertEquals(
                    InventoryVector.MSG_WITNESS_BLOCK,
                    requested.type()
            );

            assertEquals(
                    block.hash(),
                    requested.hash()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            block
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * Keep connection alive until lifecycle.close().
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

    private static void performHandshake(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) throws Exception {

        BitcoinMessage versionWire =
                reader.read(
                        input
                ).orElseThrow();

        assertEquals(
                "version",
                versionWire.command()
        );

        VersionMessage version =
                BitcoinMessages.decodeVersion(
                        versionWire
                );

        assertEquals(
                0,
                version.startHeight()
        );

        VersionMessage remoteVersion =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        0x123456789ABCDEFL,
                        "/node-lifecycle-e2e/",
                        1,
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
    }

    @Test
    void shouldFailOverToSecondPeerWhenFirstPeerDisconnectsDuringHeaderSync()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        Block genesisBlock =
                GenesisBlockFactory.create(
                        parameters
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        1
                );

        try (ServerSocket failingServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             var context =
                     new AnnotationConfigApplicationContext()) {

            CompletableFuture<Void> failingServer =
                    CompletableFuture.runAsync(
                            () -> runFailingHeaderPeer(
                                    failingServerSocket,
                                    parameters
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runLifecyclePeer(
                                    healthyServerSocket,
                                    parameters,
                                    block1
                            )
                    );

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-header-failover-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory
                                                    .resolve(
                                                            "header-failover"
                                                    )
                                                    .toString(),
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            PeerAddressManager addressManager =
                    context.getBean(
                            PeerAddressManager.class
                    );

            PeerAddress failingAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            failingServerSocket.getLocalPort(),
                            0L
                    );

            PeerAddress healthyAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            healthyServerSocket.getLocalPort(),
                            0L
                    );

            addressManager.add(
                    failingAddress,
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            addressManager.add(
                    healthyAddress,
                    Instant.ofEpochSecond(
                            1_700_000_001L
                    )
            );

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            NodeValidationService validationService =
                    context.getBean(
                            NodeValidationService.class
                    );

            NodeSyncInfrastructure syncInfrastructure =
                    context.getBean(
                            NodeSyncInfrastructure.class
                    );

            PeerManager peerManager =
                    context.getBean(
                            PeerManager.class
                    );

            assertEquals(
                    NodeLifecycleState.NEW,
                    lifecycle.state()
            );

            Thread lifecycleThread =
                    startLifecycle(
                            lifecycle
                    );

            try {

                awaitRunning(
                        lifecycle
                );

                assertTrue(
                        lifecycleThread.isAlive()
                );

                assertEquals(
                        NodeLifecycleState.RUNNING,
                        lifecycle.state()
                );

                assertTrue(
                        lifecycle.isRunning()
                );

                assertTrue(
                        lifecycle.failure()
                                .isEmpty()
                );

                assertEquals(
                        block1.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        1L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block1.hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        validationService
                                .activeTip()
                                .hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        1,
                        peerManager.size()
                );

                assertEquals(
                        1,
                        peerManager.readyPeers()
                                .size()
                );

                failingServer.get(
                        5,
                        TimeUnit.SECONDS
                );

            } finally {

                closeAndAwaitLifecycle(
                        lifecycle,
                        lifecycleThread
                );
            }

            assertTrue(
                    peerManager.isEmpty()
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailOverToSecondPeerWhenFirstPeerTimesOutDuringHeaderSync()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        Block genesisBlock =
                GenesisBlockFactory.create(
                        parameters
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        1
                );

        try (ServerSocket silentServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             var context =
                     new AnnotationConfigApplicationContext()) {

            CompletableFuture<Void> silentServer =
                    CompletableFuture.runAsync(
                            () -> runSilentHeaderPeer(
                                    silentServerSocket,
                                    parameters
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runLifecyclePeer(
                                    healthyServerSocket,
                                    parameters,
                                    block1
                            )
                    );

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-header-timeout-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory
                                                    .resolve(
                                                            "header-timeout"
                                                    )
                                                    .toString(),
                                            "bitcoin.network",
                                            "regtest",
                                            "bitcoin.p2p.header-response-timeout-millis",
                                            "150"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            PeerAddressManager addressManager =
                    context.getBean(
                            PeerAddressManager.class
                    );

            PeerAddress silentAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            silentServerSocket.getLocalPort(),
                            0L
                    );

            PeerAddress healthyAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            healthyServerSocket.getLocalPort(),
                            0L
                    );

            addressManager.add(
                    silentAddress,
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            addressManager.add(
                    healthyAddress,
                    Instant.ofEpochSecond(
                            1_700_000_001L
                    )
            );

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            NodeValidationService validationService =
                    context.getBean(
                            NodeValidationService.class
                    );

            NodeSyncInfrastructure syncInfrastructure =
                    context.getBean(
                            NodeSyncInfrastructure.class
                    );

            PeerManager peerManager =
                    context.getBean(
                            PeerManager.class
                    );

            Thread lifecycleThread =
                    startLifecycle(
                            lifecycle
                    );

            try {

                awaitRunning(
                        lifecycle
                );

                assertTrue(
                        lifecycleThread.isAlive()
                );

                assertEquals(
                        NodeLifecycleState.RUNNING,
                        lifecycle.state()
                );

                assertTrue(
                        lifecycle.isRunning()
                );

                assertTrue(
                        lifecycle.failure()
                                .isEmpty()
                );

                assertEquals(
                        block1.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        1L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block1.hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        1,
                        peerManager.size()
                );

                assertEquals(
                        1,
                        peerManager.readyPeers()
                                .size()
                );

                silentServer.get(
                        5,
                        TimeUnit.SECONDS
                );

            } finally {

                closeAndAwaitLifecycle(
                        lifecycle,
                        lifecycleThread
                );
            }

            assertTrue(
                    peerManager.isEmpty()
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldStopCleanlyDuringHeaderSynchronization()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             var context =
                     new AnnotationConfigApplicationContext()) {

            CompletableFuture<Void> getHeadersReceived =
                    new CompletableFuture<>();

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runBlockingHeaderPeer(
                                    serverSocket,
                                    parameters,
                                    getHeadersReceived
                            )
                    );

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-shutdown-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory
                                                    .resolve(
                                                            "shutdown-during-headers"
                                                    )
                                                    .toString(),
                                            "bitcoin.network",
                                            "regtest",
                                            "bitcoin.p2p.header-response-timeout-millis",
                                            "5000"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            PeerAddressManager addressManager =
                    context.getBean(
                            PeerAddressManager.class
                    );

            addressManager.add(
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    ),
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            CompletableFuture<Void> startup =
                    CompletableFuture.runAsync(
                            () -> {
                                try {

                                    lifecycle.start();

                                } catch (IOException exception) {

                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            getHeadersReceived.get(
                    5,
                    TimeUnit.SECONDS
            );

            assertEquals(
                    NodeLifecycleState.SYNCHRONIZING_HEADERS,
                    lifecycle.state()
            );

            lifecycle.close();

            startup.get(
                    5,
                    TimeUnit.SECONDS
            );

            assertEquals(
                    NodeLifecycleState.STOPPED,
                    lifecycle.state()
            );

            assertTrue(
                    lifecycle.failure()
                            .isEmpty()
            );

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldStopCleanlyDuringBlockSynchronization()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        Block genesisBlock =
                GenesisBlockFactory.create(
                        parameters
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        1
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             var context =
                     new AnnotationConfigApplicationContext()) {

            CompletableFuture<Void> getDataReceived =
                    new CompletableFuture<>();

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runBlockingBlockPeer(
                                    serverSocket,
                                    parameters,
                                    block1,
                                    getDataReceived
                            )
                    );

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "lifecycle-block-shutdown-test",
                                    Map.of(
                                            "bitcoin.data-directory",
                                            directory
                                                    .resolve(
                                                            "shutdown-during-blocks"
                                                    )
                                                    .toString(),
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class,
                    NodeConfiguration.class
            );

            context.refresh();

            PeerAddressManager addressManager =
                    context.getBean(
                            PeerAddressManager.class
                    );

            addressManager.add(
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    ),
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            NodeLifecycleService lifecycle =
                    context.getBean(
                            NodeLifecycleService.class
                    );

            PeerManager peerManager =
                    context.getBean(
                            PeerManager.class
                    );

            CompletableFuture<Void> startup =
                    CompletableFuture.runAsync(
                            () -> {
                                try {

                                    lifecycle.start();

                                } catch (IOException exception) {

                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            getDataReceived.get(
                    5,
                    TimeUnit.SECONDS
            );

            assertEquals(
                    NodeLifecycleState.SYNCHRONIZING_BLOCKS,
                    lifecycle.state()
            );

            lifecycle.close();

            startup.get(
                    5,
                    TimeUnit.SECONDS
            );

            assertEquals(
                    NodeLifecycleState.STOPPED,
                    lifecycle.state()
            );

            assertFalse(
                    lifecycle.isRunning()
            );

            assertTrue(
                    lifecycle.failure()
                            .isEmpty()
            );

            assertTrue(
                    peerManager.isEmpty()
            );

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runBlockingBlockPeer(
            ServerSocket serverSocket,
            NetworkParameters parameters,
            Block block,
            CompletableFuture<Void> getDataReceived
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
                                    parameters
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            parameters
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            BitcoinMessage firstGetHeaders =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    firstGetHeaders.command()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.headers(
                                    new HeadersMessage(
                                            List.of(
                                                    block.header()
                                            )
                                    )
                            )
                    )
            );

            output.flush();

            BitcoinMessage secondGetHeaders =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    secondGetHeaders.command()
            );

            GetHeadersMessage secondRequest =
                    GetHeadersMessageCodec.decode(
                            secondGetHeaders.payload()
                    );

            assertEquals(
                    block.hash(),
                    secondRequest
                            .locatorHashes()
                            .get(0)
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.headers(
                                    new HeadersMessage(
                                            List.of()
                                    )
                            )
                    )
            );

            output.flush();

            BitcoinMessage getDataWire =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getdata",
                    getDataWire.command()
            );

            GetDataMessage getData =
                    BitcoinMessages.decodeGetData(
                            getDataWire
                    );

            assertEquals(
                    1,
                    getData.size()
            );

            InventoryVector requested =
                    getData.inventory()
                            .get(0);

            assertEquals(
                    InventoryVector.MSG_WITNESS_BLOCK,
                    requested.type()
            );

            assertEquals(
                    block.hash(),
                    requested.hash()
            );

            getDataReceived.complete(
                    null
            );

            assertEquals(
                    -1,
                    input.read()
            );

        } catch (Exception exception) {

            getDataReceived.completeExceptionally(
                    exception
            );

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runBlockingHeaderPeer(
            ServerSocket serverSocket,
            NetworkParameters parameters,
            CompletableFuture<Void> getHeadersReceived
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
                                    parameters
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            parameters
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            BitcoinMessage getHeaders =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    getHeaders.command()
            );

            getHeadersReceived.complete(
                    null
            );

            assertEquals(
                    -1,
                    input.read()
            );

        } catch (Exception exception) {

            getHeadersReceived.completeExceptionally(
                    exception
            );

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runSilentHeaderPeer(
            ServerSocket serverSocket,
            NetworkParameters parameters
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
                                    parameters
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            parameters
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            BitcoinMessage getHeadersWire =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    getHeadersWire.command()
            );

            GetHeadersMessage request =
                    GetHeadersMessageCodec.decode(
                            getHeadersWire.payload()
                    );

            assertFalse(
                    request.locatorHashes()
                            .isEmpty()
            );

            Thread.sleep(
                    500
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runFailingHeaderPeer(
            ServerSocket serverSocket,
            NetworkParameters parameters
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
                                    parameters
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            parameters
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            BitcoinMessage getHeadersWire =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "getheaders",
                    getHeadersWire.command()
            );

            GetHeadersMessage request =
                    GetHeadersMessageCodec.decode(
                            getHeadersWire.payload()
                    );

            assertFalse(
                    request.locatorHashes()
                            .isEmpty()
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static Thread startLifecycle(
            NodeLifecycleService lifecycle
    ) {

        return Thread.ofPlatform()
                .name(
                        "node-lifecycle-test"
                )
                .start(
                        () -> {
                            try {

                                lifecycle.start();

                            } catch (IOException exception) {

                                throw new RuntimeException(
                                        exception
                                );
                            }
                        }
                );
    }

    private static void awaitRunning(
            NodeLifecycleService lifecycle
    ) throws InterruptedException {

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(
                        5
                );

        while (!lifecycle.isRunning()
                && lifecycle.state()
                != NodeLifecycleState.FAILED
                && System.nanoTime() < deadline) {

            Thread.sleep(
                    10
            );
        }

        assertEquals(
                NodeLifecycleState.RUNNING,
                lifecycle.state(),
                () -> "Lifecycle did not reach RUNNING; failure="
                        + lifecycle.failure()
        );
    }

    private static void closeAndAwaitLifecycle(
            NodeLifecycleService lifecycle,
            Thread lifecycleThread
    ) throws Exception {

        lifecycle.close();

        lifecycleThread.join(
                5_000
        );

        assertFalse(
                lifecycleThread.isAlive(),
                "Lifecycle thread did not terminate after close()"
        );

        assertEquals(
                NodeLifecycleState.STOPPED,
                lifecycle.state()
        );

        assertFalse(
                lifecycle.isRunning()
        );
    }

    private static Block child(
            BlockIndex parent,
            int tag
    ) {

        int height =
                Math.toIntExact(
                        parent.height() + 1
                );

        Transaction coinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                (byte) (0x50 + height),
                                                (byte) tag
                                        },
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        REWARD,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        Hash256 merkleRoot =
                MerkleTree.calculateRoot(
                        List.of(
                                coinbase.txId()
                        )
                );

        Block template =
                new Block(
                        new BlockHeader(
                                4,
                                parent.hash(),
                                merkleRoot,
                                new UInt32(
                                        parent.header()
                                                .timestamp()
                                                .value() + 1
                                ),
                                new UInt32(
                                        BITS
                                ),
                                new UInt32(0)
                        ),
                        List.of(
                                coinbase
                        )
                );

        return withValidPow(
                template,
                parent.hash(),
                merkleRoot
        );
    }

    private static Block withValidPow(
            Block block,
            Hash256 parentHash,
            Hash256 merkleRoot
    ) {

        for (long nonce = 0;
             nonce < 100_000;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            block.header()
                                    .version(),
                            parentHash,
                            merkleRoot,
                            block.header()
                                    .timestamp(),
                            block.header()
                                    .bits(),
                            new UInt32(
                                    nonce
                            )
                    );

            if (ProofOfWork.isValid(
                    header,
                    NetworkParametersRegistry.regtest()
            )) {

                return new Block(
                        header,
                        block.transactions()
                );
            }
        }

        throw new IllegalStateException(
                "Unable to find valid regtest nonce"
        );
    }
}