package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayCompactBlockTest {

    @TempDir
    Path directory;

    @Test
    void completeReconstructionFailureRequestsAndAcceptsFullBlock() throws Exception {
        reconstructionFailureFallsBack(false);
    }

    @Test
    void partialReconstructionFailureRequestsAndAcceptsFullBlock() throws Exception {
        reconstructionFailureFallsBack(true);
    }

    private void reconstructionFailureFallsBack(boolean partial) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("fallback")); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(compactCapableVersion());
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            var outgoing = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(call -> { outgoing.add(call.getArgument(0)); return null; }).when(peer).send(any());
            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var mining = new MiningController(validation, relay, parameters, () -> true,
                        new byte[]{0x51}, 4_000_000, new FeeRate(0));
                Block block = MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
                var coinbase = block.transactions().getFirst();
                var outputs = new ArrayList<>(coinbase.outputs());
                var first = outputs.getFirst();
                outputs.set(0, new ru.bitcoin.node.protocol.transaction.TxOut(first.value() - 1, first.scriptPubKey()));
                var wrong = new ru.bitcoin.node.protocol.transaction.Transaction(coinbase.version(), coinbase.inputs(),
                        outputs, coinbase.lockTime());
                var full = new BitcoinMessage("block", ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(block));
                // Unsolicited full blocks are not admitted by this fallback path.
                incoming.get().onMessage(peer, full);
                incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(false, 2)));
                var compact = partial
                        ? new CompactBlockMessage(block.header(), 42, List.of(1L), List.of())
                        : new CompactBlockMessage(block.header(), 42, List.of(), List.of(new PrefilledTransaction(0, wrong)));
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(compact, 2));
                if (partial) {
                    assertEquals("getblocktxn", take(outgoing).command());
                    incoming.get().onMessage(peer, BitcoinMessages.blockTxn(
                            new BlockTransactionsMessage(block.hash(), List.of(wrong)), 2));
                }
                var fallback = take(outgoing);
                assertEquals("getdata", fallback.command());
                assertEquals(List.of(new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, block.hash())),
                        BitcoinMessages.decodeGetData(fallback).inventory());
                assertFalse(new ru.bitcoin.node.storage.block.RocksDbBlockFailureStore(db).isFailed(block.hash()));
                assertTrue(validation.findBlock(block.hash()).isEmpty());
                // No duplicate fallback request; the expected full block is validated and connected.
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(compact, 2));
                incoming.get().onMessage(peer, full);
                assertEquals("sendcmpct", take(outgoing).command());
                assertTrue(validation.findBlock(block.hash()).isPresent());
                verify(peer, never()).close();
            }
        }
    }

    @Test
    void duplicateCompactAnnouncementKeepsOriginalRequestAndCompletesBlock() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("pending-duplicate")); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(compactCapableVersion());
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            var outgoing = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(call -> { outgoing.add(call.getArgument(0)); return null; }).when(peer).send(any());
            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var mining = new MiningController(validation, relay, parameters, () -> true,
                        new byte[]{0x51}, 4_000_000, new FeeRate(0));
                Block block = MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
                incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(false, 2)));
                var compact = new CompactBlockMessage(block.header(), 42, List.of(1L), List.of());
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(compact, 2));
                BitcoinMessage request = take(outgoing);
                assertEquals("getblocktxn", request.command());
                assertEquals(List.of(0), BitcoinMessages.decodeGetBlockTxn(request).indexes());
                // A duplicate with a different layout must neither overwrite nor re-request the partial.
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(
                        new CompactBlockMessage(block.header(), 43, List.of(1L, 2L), List.of()), 2));
                incoming.get().onMessage(peer, BitcoinMessages.blockTxn(
                        new BlockTransactionsMessage(block.hash(), block.transactions()), 2));
                BitcoinMessage promotion = take(outgoing);
                assertEquals("sendcmpct", promotion.command());
                assertTrue(validation.findBlock(block.hash()).isPresent());
                verify(peer, never()).close();
            }
        }
    }

    @Test
    void highBandwidthPeerGetsCompactAnnouncementAndCanRequestTransactions() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();

        try (var db = new RocksDbDatabase(directory.resolve("bip152"));
             var peers = new PeerManager()) {

            var validation = new NodeValidationService(
                    db,
                    parameters,
                    () -> 1_800_000_000L,
                    new Mempool()
            );

            var sync = new NodeSyncInfrastructure(
                    db,
                    parameters,
                    () -> 1_800_000_000L
            );

            var peer = mock(Peer.class);

            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(compactCapableVersion());

            var incoming =
                    new AtomicReference<PeerMessageListener>();

            doAnswer(invocation -> {
                incoming.set(invocation.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());

            var outbound =
                    new LinkedBlockingQueue<BitcoinMessage>();

            doAnswer(invocation -> {
                outbound.add(invocation.getArgument(0));
                return null;
            }).when(peer).send(any());

            peers.add(peer);

            try (var relay =
                         new NodeRelayService(
                                 validation,
                                 sync,
                                 peers
                         )) {

                /*
                 * Initial SENDCMPCT(false, 2) is now part of the real
                 * Peer handshake feature negotiation. This test uses
                 * a mocked READY Peer and therefore must not expect
                 * NodeRelayService to send the initial advertisement.
                 *
                 * The remote peer asks us to announce new blocks using
                 * high-bandwidth compact-block relay.
                 */
                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.sendCmpct(
                                new SendCmpctMessage(true, 2)
                        )
                );

                var mining =
                        new MiningController(
                                validation,
                                relay,
                                parameters,
                                () -> true,
                                new byte[]{0x51},
                                4_000_000,
                                new FeeRate(0)
                        );

                Map<?, ?> template =
                        mining.getBlockTemplate(
                                Map.of(
                                        "rules",
                                        List.of("segwit")
                                )
                        );

                Block mined =
                        MiningRpcTest.mineTemplate(template);

                assertEquals(
                        ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                        relay.submitBlock(mined)
                );

                BitcoinMessage compactWire =
                        take(outbound);

                assertEquals(
                        "cmpctblock",
                        compactWire.command()
                );

                CompactBlockMessage compact =
                        BitcoinMessages.decodeCompactBlock(
                                compactWire,
                                2
                        );

                assertEquals(
                        mined.hash(),
                        compact.header().hash()
                );

                assertEquals(
                        mined.transactions().size(),
                        compact.transactionCount()
                );

                assertEquals(
                        0,
                        compact.prefilledTransactions()
                                .getFirst()
                                .index()
                );

                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.getBlockTxn(
                                new BlockTransactionsRequest(
                                        mined.hash(),
                                        List.of(0)
                                )
                        )
                );

                BitcoinMessage blockTxnWire =
                        take(outbound);

                assertEquals(
                        "blocktxn",
                        blockTxnWire.command()
                );

                BlockTransactionsMessage response =
                        BitcoinMessages.decodeBlockTxn(
                                blockTxnWire,
                                2
                        );

                assertEquals(
                        mined.hash(),
                        response.blockHash()
                );

                assertEquals(
                        List.of(
                                mined.transactions().getFirst()
                        ),
                        response.transactions()
                );
            }
        }
    }

    @Test
    void unsupportedSendCmpctVersionIsIgnoredWithoutDisconnectingPeer()
            throws Exception {

        var parameters =
                NetworkParametersRegistry.regtest();

        try (var db =
                     new RocksDbDatabase(
                             directory.resolve("unsupported")
                     );
             var peers = new PeerManager()) {

            var validation =
                    new NodeValidationService(
                            db,
                            parameters,
                            () -> 1_800_000_000L,
                            new Mempool()
                    );

            var sync =
                    new NodeSyncInfrastructure(
                            db,
                            parameters,
                            () -> 1_800_000_000L
                    );

            var peer =
                    mock(Peer.class);

            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion())
                    .thenReturn(compactCapableVersion());

            var incoming =
                    new AtomicReference<PeerMessageListener>();

            doAnswer(invocation -> {
                incoming.set(invocation.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());

            peers.add(peer);

            try (var relay =
                         new NodeRelayService(
                                 validation,
                                 sync,
                                 peers
                         )) {

                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.sendCmpct(
                                new SendCmpctMessage(
                                        true,
                                        99
                                )
                        )
                );

                Thread.sleep(100);

                verify(
                        peer,
                        never()
                ).close();
            }
        }
    }

    @Test
    void validCompactBlockPromotesProviderToHighBandwidthMode()
            throws Exception {

        var parameters =
                NetworkParametersRegistry.regtest();

        try (var db =
                     new RocksDbDatabase(
                             directory.resolve("promote")
                     );
             var peers = new PeerManager()) {

            var validation =
                    new NodeValidationService(
                            db,
                            parameters,
                            () -> 1_800_000_000L,
                            new Mempool()
                    );

            var sync =
                    new NodeSyncInfrastructure(
                            db,
                            parameters,
                            () -> 1_800_000_000L
                    );

            var peer =
                    mock(Peer.class);

            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion())
                    .thenReturn(compactCapableVersion());

            var incoming =
                    new AtomicReference<PeerMessageListener>();

            doAnswer(invocation -> {
                incoming.set(invocation.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());

            var outbound =
                    new LinkedBlockingQueue<BitcoinMessage>();

            doAnswer(invocation -> {
                outbound.add(invocation.getArgument(0));
                return null;
            }).when(peer).send(any());

            peers.add(peer);

            try (var relay =
                         new NodeRelayService(
                                 validation,
                                 sync,
                                 peers
                         )) {

                /*
                 * Tell our relay layer that this remote peer supports
                 * compact blocks. The initial local
                 * SENDCMPCT(false, 2) belongs to Peer handshake and
                 * is intentionally not produced by NodeRelayService.
                 */
                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.sendCmpct(
                                new SendCmpctMessage(
                                        false,
                                        2
                                )
                        )
                );

                var mining =
                        new MiningController(
                                validation,
                                relay,
                                parameters,
                                () -> true,
                                new byte[]{0x51},
                                4_000_000,
                                new FeeRate(0)
                        );

                Map<?, ?> template =
                        mining.getBlockTemplate(
                                Map.of(
                                        "rules",
                                        List.of("segwit")
                                )
                        );

                Block mined =
                        MiningRpcTest.mineTemplate(template);

                CompactBlockMessage compact =
                        CompactBlockFactory.create(
                                mined,
                                42L,
                                2
                        );

                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.compactBlock(
                                compact,
                                2
                        )
                );

                BitcoinMessage promotion =
                        take(outbound);

                assertEquals(
                        "sendcmpct",
                        promotion.command()
                );

                assertEquals(
                        new SendCmpctMessage(true, 2),
                        BitcoinMessages.decodeSendCmpct(
                                promotion
                        )
                );
            }
        }
    }

    private static VersionMessage compactCapableVersion() {

        return new VersionMessage(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                VersionMessage.DEFAULT_SERVICES,
                0,
                NetworkAddress.unspecified(),
                NetworkAddress.unspecified(),
                1,
                "/test/",
                0,
                true
        );
    }

    private static BitcoinMessage take(
            BlockingQueue<BitcoinMessage> queue
    ) throws InterruptedException {

        BitcoinMessage message =
                queue.poll(
                        5,
                        TimeUnit.SECONDS
                );

        assertNotNull(
                message,
                "Expected network message"
        );

        return message;
    }
}
