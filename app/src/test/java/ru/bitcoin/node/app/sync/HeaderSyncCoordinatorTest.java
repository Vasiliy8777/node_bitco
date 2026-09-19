package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.codec.HeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderSyncCoordinatorTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    @TempDir
    Path tempDirectory;

    @Test
    void shouldContinueGetHeadersFromLastProcessedHeader()
            throws Exception {

        BlockIndex genesis =
                createGenesisIndex();

        BlockHeader header1 =
                findValidHeader(
                        genesis.hash(),
                        1_700_000_001L
                );

        BlockHeader header2 =
                findValidHeader(
                        header1.hash(),
                        1_700_000_002L
                );

        BlockHeader header3 =
                findValidHeader(
                        header2.hash(),
                        1_700_000_003L
                );

        Hash256 stopHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "header-sync-coordinator"
                             )
                     )) {

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            chainStateStore.saveBestHeaderTipHash(
                    genesis.hash()
            );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            genesis
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexStore,
                            chainStateStore
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
                    );

            BlockLocatorBuilder locatorBuilder =
                    new BlockLocatorBuilder(
                            lookup
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    genesis.hash(),
                                    header2.hash(),
                                    header3.hash(),
                                    stopHash,
                                    header1,
                                    header2,
                                    header3
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
                                 5_000,
                                 5_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                connection,
                                peer
                        );

                HeaderSyncCoordinator coordinator =
                        new HeaderSyncCoordinator(
                                headerSynchronizer,
                                headerSyncService,
                                headerChainState,
                                locatorBuilder
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize(
                                stopHash
                        );

                assertEquals(
                        3,
                        processed.size()
                );

                assertEquals(
                        header1.hash(),
                        processed.get(0).hash()
                );

                assertEquals(
                        header2.hash(),
                        processed.get(1).hash()
                );

                assertEquals(
                        header3.hash(),
                        processed.get(2).hash()
                );

                assertEquals(
                        header3.hash(),
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        header3.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runPeer(
            ServerSocket serverSocket,
            Hash256 genesisHash,
            Hash256 secondBatchLocator,
            Hash256 thirdBatchLocator,
            Hash256 stopHash,
            BlockHeader header1,
            BlockHeader header2,
            BlockHeader header3
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    REGTEST
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            REGTEST
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            assertGetHeaders(
                    reader,
                    input,
                    genesisHash,
                    stopHash
            );

            sendHeaders(
                    encoder,
                    output,
                    List.of(
                            header1,
                            header2
                    )
            );

            /*
             * After the first batch the synchronization
             * cursor must be header2.
             */
            assertGetHeaders(
                    reader,
                    input,
                    secondBatchLocator,
                    stopHash
            );

            sendHeaders(
                    encoder,
                    output,
                    List.of(
                            header3
                    )
            );

            /*
             * After the second batch the cursor must
             * advance to header3.
             */
            assertGetHeaders(
                    reader,
                    input,
                    thirdBatchLocator,
                    stopHash
            );

            /*
             * Empty headers terminates synchronization.
             */
            sendHeaders(
                    encoder,
                    output,
                    List.of()
            );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertGetHeaders(
            BitcoinMessageStreamReader reader,
            BufferedInputStream input,
            Hash256 expectedFirstLocator,
            Hash256 expectedStopHash
    ) throws Exception {

        BitcoinMessage wire =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "getheaders",
                wire.command()
        );

        GetHeadersMessage message =
                GetHeadersMessageCodec.decode(
                        wire.payload()
                );

        assertEquals(
                70016,
                message.protocolVersion()
        );

        assertEquals(
                expectedFirstLocator,
                message.locatorHashes().get(0)
        );

        assertEquals(
                expectedStopHash,
                message.stopHash()
        );
    }

    private static void sendHeaders(
            BitcoinMessageEncoder encoder,
            BufferedOutputStream output,
            List<BlockHeader> headers
    ) throws Exception {

        HeadersMessage response =
                new HeadersMessage(
                        headers
                );

        output.write(
                encoder.encode(
                        new BitcoinMessage(
                                "headers",
                                HeadersMessageCodec.encode(
                                        response
                                )
                        )
                )
        );

        output.flush();
    }
    @Test
    void shouldContinueCompetingBranchFromPeerCursor()
            throws Exception {

        BlockIndex genesis =
                createGenesisIndex();

        /*
         * Existing best chain:
         *
         * genesis -> A1 -> A2 -> A3
         */
        BlockHeader headerA1 =
                findValidHeader(
                        genesis.hash(),
                        1_700_000_010L
                );

        BlockIndex indexA1 =
                BlockIndexFactory.createChild(
                        genesis,
                        headerA1
                );

        BlockHeader headerA2 =
                findValidHeader(
                        headerA1.hash(),
                        1_700_000_011L
                );

        BlockIndex indexA2 =
                BlockIndexFactory.createChild(
                        indexA1,
                        headerA2
                );

        BlockHeader headerA3 =
                findValidHeader(
                        headerA2.hash(),
                        1_700_000_012L
                );

        BlockIndex indexA3 =
                BlockIndexFactory.createChild(
                        indexA2,
                        headerA3
                );

        /*
         * Competing branch received from the peer:
         *
         * genesis -> B1 -> B2 -> B3 -> B4
         *
         * After B1/B2, A3 must still be the global
         * best header because A has more chainwork.
         *
         * Nevertheless the next getheaders must continue
         * from B2, not from A3.
         */
        BlockHeader headerB1 =
                findValidHeader(
                        genesis.hash(),
                        1_700_000_020L
                );

        BlockHeader headerB2 =
                findValidHeader(
                        headerB1.hash(),
                        1_700_000_021L
                );

        BlockHeader headerB3 =
                findValidHeader(
                        headerB2.hash(),
                        1_700_000_022L
                );

        BlockHeader headerB4 =
                findValidHeader(
                        headerB3.hash(),
                        1_700_000_023L
                );

        Hash256 stopHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "header-sync-competing-branch"
                             )
                     )) {

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            /*
             * Persist genesis and the already-known A chain.
             */
            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            indexA1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            indexA2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            indexA3
                    )
            );

            chainStateStore.saveBestHeaderTipHash(
                    indexA3.hash()
            );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            indexA3
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexStore,
                            chainStateStore
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
                    );

            BlockLocatorBuilder locatorBuilder =
                    new BlockLocatorBuilder(
                            lookup
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runCompetingBranchPeer(
                                    serverSocket,
                                    indexA3.hash(),
                                    headerB2.hash(),
                                    headerB4.hash(),
                                    stopHash,
                                    headerB1,
                                    headerB2,
                                    headerB3,
                                    headerB4
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
                                 5_000,
                                 5_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                connection,
                                peer
                        );

                HeaderSyncCoordinator coordinator =
                        new HeaderSyncCoordinator(
                                headerSynchronizer,
                                headerSyncService,
                                headerChainState,
                                locatorBuilder
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize(
                                stopHash
                        );

                assertEquals(
                        4,
                        processed.size()
                );

                assertEquals(
                        headerB1.hash(),
                        processed.get(0).hash()
                );

                assertEquals(
                        headerB2.hash(),
                        processed.get(1).hash()
                );

                assertEquals(
                        headerB3.hash(),
                        processed.get(2).hash()
                );

                assertEquals(
                        headerB4.hash(),
                        processed.get(3).hash()
                );

                /*
                 * Four B blocks have more cumulative work
                 * than the three-block A branch, so B4
                 * must eventually become global best.
                 */
                assertEquals(
                        headerB4.hash(),
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        headerB4.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runCompetingBranchPeer(
            ServerSocket serverSocket,
            Hash256 initialLocator,
            Hash256 secondLocator,
            Hash256 thirdLocator,
            Hash256 stopHash,
            BlockHeader headerB1,
            BlockHeader headerB2,
            BlockHeader headerB3,
            BlockHeader headerB4
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    REGTEST
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            REGTEST
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            /*
             * Session starts from the global best A3.
             */
            assertGetHeaders(
                    reader,
                    input,
                    initialLocator,
                    stopHash
            );

            /*
             * B1/B2 are valid, but B2 has less chainwork
             * than A3.
             */
            sendHeaders(
                    encoder,
                    output,
                    List.of(
                            headerB1,
                            headerB2
                    )
            );

            /*
             * CRITICAL ASSERTION:
             *
             * The next request must continue from B2 even
             * though global best is still A3.
             */
            assertGetHeaders(
                    reader,
                    input,
                    secondLocator,
                    stopHash
            );

            sendHeaders(
                    encoder,
                    output,
                    List.of(
                            headerB3,
                            headerB4
                    )
            );

            /*
             * B4 has now overtaken the A branch.
             */
            assertGetHeaders(
                    reader,
                    input,
                    thirdLocator,
                    stopHash
            );

            sendHeaders(
                    encoder,
                    output,
                    List.of()
            );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldSynchronizeAcrossMaximumHeadersBatchBoundary()
            throws Exception {

        BlockIndex genesis =
                createGenesisIndex();

        /*
         * Build 2005 sequential PoW-valid regtest headers.
         *
         * Expected wire sequence:
         *
         * getheaders #1 -> 2000 headers
         * getheaders #2 ->    5 headers
         * getheaders #3 ->    0 headers
         */
        List<BlockHeader> headers =
                new java.util.ArrayList<>(
                        2005
                );

        Hash256 previousHash =
                genesis.hash();

        for (int height = 1;
             height <= 2005;
             height++) {

            BlockHeader header =
                    findValidHeader(
                            previousHash,
                            1_700_001_000L + height
                    );

            headers.add(
                    header
            );

            previousHash =
                    header.hash();
        }

        BlockHeader header2000 =
                headers.get(1999);

        BlockHeader header2005 =
                headers.get(2004);

        Hash256 stopHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "header-sync-2000-boundary"
                             )
                     )) {

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            chainStateStore.saveBestHeaderTipHash(
                    genesis.hash()
            );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            genesis
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexStore,
                            chainStateStore
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
                    );

            BlockLocatorBuilder locatorBuilder =
                    new BlockLocatorBuilder(
                            lookup
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runMaximumHeadersPeer(
                                    serverSocket,
                                    genesis.hash(),
                                    header2000.hash(),
                                    header2005.hash(),
                                    stopHash,
                                    headers
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
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
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                connection,
                                peer
                        );

                HeaderSyncCoordinator coordinator =
                        new HeaderSyncCoordinator(
                                headerSynchronizer,
                                headerSyncService,
                                headerChainState,
                                locatorBuilder
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize(
                                stopHash
                        );

                assertEquals(
                        2005,
                        processed.size()
                );

                assertEquals(
                        1L,
                        processed.get(0).height()
                );

                assertEquals(
                        2000L,
                        processed.get(1999).height()
                );

                assertEquals(
                        2001L,
                        processed.get(2000).height()
                );

                assertEquals(
                        2005L,
                        processed.get(2004).height()
                );

                /*
                 * Verify chain continuity exactly across
                 * the 2000-header response boundary.
                 */
                assertEquals(
                        processed.get(1999).hash(),
                        processed.get(2000)
                                .previousBlockHash()
                );

                assertEquals(
                        header2005.hash(),
                        processed.get(2004).hash()
                );

                assertEquals(
                        header2005.hash(),
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        2005L,
                        headerChainState
                                .bestHeaderTip()
                                .height()
                );

                assertEquals(
                        header2005.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );

                BlockIndex storedTip =
                        lookup.find(
                                header2005.hash()
                        );

                assertEquals(
                        2005L,
                        storedTip.height()
                );
            }

            server.get(
                    10,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runMaximumHeadersPeer(
            ServerSocket serverSocket,
            Hash256 initialLocator,
            Hash256 secondLocator,
            Hash256 thirdLocator,
            Hash256 stopHash,
            List<BlockHeader> headers
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    10_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    REGTEST
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            REGTEST
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            /*
             * Request #1 starts from genesis.
             */
            assertGetHeaders(
                    reader,
                    input,
                    initialLocator,
                    stopHash
            );

            /*
             * Maximum permitted headers response.
             */
            sendHeaders(
                    encoder,
                    output,
                    headers.subList(
                            0,
                            2000
                    )
            );

            /*
             * CRITICAL:
             * after exactly 2000 processed headers the next
             * locator must start from header #2000.
             */
            assertGetHeaders(
                    reader,
                    input,
                    secondLocator,
                    stopHash
            );

            sendHeaders(
                    encoder,
                    output,
                    headers.subList(
                            2000,
                            2005
                    )
            );

            /*
             * Five additional headers were processed.
             * The next locator must therefore start from
             * header #2005.
             */
            assertGetHeaders(
                    reader,
                    input,
                    thirdLocator,
                    stopHash
            );

            /*
             * Empty response is the synchronization
             * completion signal.
             */
            sendHeaders(
                    encoder,
                    output,
                    List.of()
            );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void performHandshake(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) throws Exception {

        BitcoinMessage version =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "version",
                version.command()
        );

        VersionMessage remoteVersion =
                new VersionMessage(
                        70016,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        REMOTE_NONCE,
                        "/header-sync-coordinator-test/",
                        0,
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

    private static BlockIndex createGenesisIndex() {

        Hash256 previous =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        BlockHeader header =
                findValidHeader(
                        previous,
                        1_700_000_000L
                );

        return new BlockIndex(
                header.hash(),
                header,
                0L,
                previous,
                BigInteger.ONE
        );
    }

    private static BlockHeader findValidHeader(
            Hash256 previousBlockHash,
            long timestamp
    ) {

        for (long nonce = 0;
             nonce <= UInt32.MAX_VALUE;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            previousBlockHash,
                            Hash256.fromDisplayHex(
                                    "11".repeat(32)
                            ),
                            new UInt32(
                                    timestamp
                            ),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    nonce
                            )
                    );

            if (ProofOfWork.isValid(
                    header,
                    REGTEST
            )) {
                return header;
            }
        }

        throw new IllegalStateException(
                "Could not find valid regtest nonce"
        );
    }
}