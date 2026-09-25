package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayServiceTest {
    @TempDir Path directory;

    @Test
    void newBlockAnnouncementRunsBetweenHistoricalBlockResponses() throws Exception {
        var validation = mock(NodeValidationService.class);
        var sync = mock(NodeSyncInfrastructure.class);
        Block historical = ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        when(validation.findBlock(historical.hash())).thenReturn(Optional.of(historical));
        var peer = mock(Peer.class);
        when(peer.isReady()).thenReturn(true);
        var incoming = new AtomicReference<PeerMessageListener>();
        doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var sent = new LinkedBlockingQueue<BitcoinMessage>();
        doAnswer(call -> {
            BitcoinMessage message = call.getArgument(0);
            sent.add(message);
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("send barrier timeout");
            }
            return null;
        }).when(peer).send(any());
        try (var peers = new PeerManager()) {
            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var vector = new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, historical.hash());
                incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(Collections.nCopies(300, vector))));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var fresh = mock(Block.class);
                when(fresh.hash()).thenReturn(Hash256.fromDisplayHex("12".repeat(32)));
                relay.relayConnectedBlock(fresh, null);
                release.countDown();
                assertEquals("block", take(sent).command());
                var announcement = take(sent);
                assertEquals("inv", announcement.command());
                assertEquals(fresh.hash(), BitcoinMessages.decodeInv(announcement).inventory().getFirst().hash());
                for (int i = 1; i < 300; i++) assertEquals("block", take(sent).command());
                verify(peer, never()).close();
                verify(validation, never()).mempoolEntries();
            } finally {
                release.countDown();
            }
        }
    }

    @Test void requestsMissingParentRetriesChildAndServesAdmittedTransaction() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        byte[] script = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(new byte[]{0x51})) + "87");
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var fund = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, script, 0, false));
            var parent = spend(fund, 90_000, script);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000, script);
            var source = mock(Peer.class);
            var destination = mock(Peer.class);
            var version = mock(VersionMessage.class);
            when(version.relay()).thenReturn(true);
            when(source.isReady()).thenReturn(true);
            when(destination.isReady()).thenReturn(true);
            when(source.remoteVersion()).thenReturn(version);
            when(destination.remoteVersion()).thenReturn(version);
            when(destination.remoteWtxidRelay()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { incoming.set(invocation.getArgument(0)); return null; }).when(source).addMessageListener(any());
            var requests = new LinkedBlockingQueue<BitcoinMessage>();
            var announcements = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { requests.add(invocation.getArgument(0)); return null; }).when(source).send(any());
            doAnswer(invocation -> { announcements.add(invocation.getArgument(0)); return null; }).when(destination).send(any());
            peers.add(source);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                peers.add(destination); // New connections must be observed too.
                incoming.get().onMessage(source, BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(5, child.wtxId())))));
                assertEquals(child.wtxId(), BitcoinMessages.decodeGetData(take(requests)).inventory().getFirst().hash());
                incoming.get().onMessage(source, new BitcoinMessage("tx", TransactionSerializer.serialize(child)));
                assertEquals(parent.txId(), BitcoinMessages.decodeGetData(take(requests)).inventory().getFirst().hash());
                assertTrue(validation.mempoolEntries().isEmpty());
                incoming.get().onMessage(source, new BitcoinMessage("tx", TransactionSerializer.serialize(parent)));
                BitcoinMessage relayed = take(announcements);
                assertEquals("inv", relayed.command());
                List<InventoryVector> relayedInventory =
                        BitcoinMessages.decodeInv(relayed).inventory();
                assertEquals(
                        List.of(parent.wtxId(), child.wtxId()),
                        relayedInventory.stream()
                                .map(InventoryVector::hash)
                                .toList()
                );
                assertEquals(2, validation.mempoolEntries().size());
                /*
                 * The source peer originally announced the child's wtxid to us,
                 * so the per-peer known-inventory state must suppress announcing
                 * the same transaction back to that peer.
                 */
                assertNull(
                        requests.poll(250, TimeUnit.MILLISECONDS),
                        "Transaction already announced by the source peer must not be announced back"
                );

                /*
                 * BIP133 / known-inventory suppression affects announcements only.
                 * An explicit GETDATA from the source must still be served.
                 */
                incoming.get().onMessage(
                        source,
                        BitcoinMessages.getData(
                                new GetDataMessage(
                                        List.of(
                                                new InventoryVector(
                                                        5,
                                                        child.wtxId()
                                                )
                                        )
                                )
                        )
                );
                var served = take(requests);
                assertEquals("tx", served.command());
                assertArrayEquals(TransactionSerializer.serialize(child), served.payload());
                var unknown = new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, new Hash256(new byte[32]));
                incoming.get().onMessage(source, BitcoinMessages.getData(new GetDataMessage(List.of(unknown))));
                assertEquals(List.of(unknown), BitcoinMessages.decodeNotFound(take(requests)).inventory());
            }
            verify(source).removeMessageListener(incoming.get());
        }
    }

    @Test
    void announcesConnectedBlockAndServesItAfterGetData()
            throws Exception {

        var parameters =
                NetworkParametersRegistry.regtest();

        try (var db =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-relay"
                             )
                     );

             var peers =
                     new PeerManager()) {

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
                    mock(
                            Peer.class
                    );

            when(
                    peer.isReady()
            ).thenReturn(
                    true
            );

            var incoming =
                    new AtomicReference<PeerMessageListener>();

            doAnswer(invocation -> {

                incoming.set(
                        invocation.getArgument(
                                0
                        )
                );

                return null;

            }).when(
                    peer
            ).addMessageListener(
                    any()
            );

            var outbound =
                    new LinkedBlockingQueue<BitcoinMessage>();

            doAnswer(invocation -> {

                outbound.add(
                        invocation.getArgument(
                                0
                        )
                );

                return null;

            }).when(
                    peer
            ).send(
                    any()
            );

            peers.add(
                    peer
            );

            try (var relay =
                         new NodeRelayService(
                                 validation,
                                 sync,
                                 peers
                         )) {

                var mining =
                        new ru.bitcoin.node.app.rpc.MiningController(
                                validation,
                                relay,
                                parameters,
                                () -> true,
                                new byte[]{0x51},
                                4_000_000,
                                new ru.bitcoin.node.mempool.FeeRate(
                                        0
                                )
                        );

                /*
                 * Use the real mining-template path already used
                 * by MiningRpcTest.
                 */
                Map<?, ?> template =
                        mining.getBlockTemplate(
                                Map.of(
                                        "rules",
                                        List.of(
                                                "segwit"
                                        )
                                )
                        );

                var mined =
                        MiningRpcTest.mineTemplate(
                                template
                        );

                /*
                 * Local mining submission must connect the block.
                 */
                assertEquals(
                        ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                        relay.submitBlock(
                                mined
                        )
                );

                assertEquals(
                        mined.hash(),
                        validation.activeTip()
                                .hash()
                );

                assertEquals(
                        mined.hash(),
                        sync.headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                /*
                 * A READY peer must receive an INV for the newly
                 * connected block.
                 */
                BitcoinMessage announcement =
                        take(
                                outbound
                        );

                assertEquals(
                        "inv",
                        announcement.command()
                );

                InvMessage inventory =
                        BitcoinMessages.decodeInv(
                                announcement
                        );

                assertEquals(
                        1,
                        inventory.inventory()
                                .size()
                );

                InventoryVector announced =
                        inventory.inventory()
                                .getFirst();

                assertEquals(
                        InventoryVector.MSG_BLOCK,
                        announced.type()
                );

                assertEquals(
                        mined.hash(),
                        announced.hash()
                );

                /*
                 * Simulate Bitcoin Core requesting the announced
                 * block with witness serialization.
                 */
                incoming.get()
                        .onMessage(
                                peer,
                                BitcoinMessages.getData(
                                        new GetDataMessage(
                                                List.of(
                                                        new InventoryVector(
                                                                InventoryVector.MSG_WITNESS_BLOCK,
                                                                mined.hash()
                                                        )
                                                )
                                        )
                                )
                        );

                BitcoinMessage blockMessage =
                        take(
                                outbound
                        );

                assertEquals(
                        "block",
                        blockMessage.command()
                );

                assertArrayEquals(
                        ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(
                                mined
                        ),
                        blockMessage.payload()
                );
            }

            verify(
                    peer
            ).removeMessageListener(
                    incoming.get()
            );
        }
    }


    @Test
    void acceptsGetDataAt1291000And50000InventoryEntries() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("large-getdata"));
             var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);

            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { incoming.set(invocation.getArgument(0)); return null; })
                    .when(peer).addMessageListener(any());
            var outbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { outbound.add(invocation.getArgument(0)); return null; })
                    .when(peer).send(any());

            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                for (int count : List.of(129, 1_000, 50_000)) {
                    List<InventoryVector> inventory = unknownTransactions(count, count);
                    incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(inventory)));

                    BitcoinMessage response = take(outbound);
                    assertEquals("notfound", response.command());
                    assertEquals(inventory, BitcoinMessages.decodeNotFound(response).inventory());
                    assertTrue(peer.isReady(), "Valid GETDATA size must not disconnect the peer");
                }
            }

            verify(peer, never()).close();
        }
    }

    @Test
    void slowGetDataPeerDoesNotBlockAnotherPeer() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("slow-peer-isolation"));
             var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);

            var slow = mock(Peer.class);
            var fast = mock(Peer.class);
            when(slow.isReady()).thenReturn(true);
            when(fast.isReady()).thenReturn(true);

            var slowIncoming = new AtomicReference<PeerMessageListener>();
            var fastIncoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { slowIncoming.set(invocation.getArgument(0)); return null; })
                    .when(slow).addMessageListener(any());
            doAnswer(invocation -> { fastIncoming.set(invocation.getArgument(0)); return null; })
                    .when(fast).addMessageListener(any());

            var slowSendEntered = new CountDownLatch(1);
            var releaseSlowSend = new CountDownLatch(1);
            doAnswer(invocation -> {
                slowSendEntered.countDown();
                if (!releaseSlowSend.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out waiting to release slow peer");
                }
                return null;
            }).when(slow).send(any());

            var fastOutbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { fastOutbound.add(invocation.getArgument(0)); return null; })
                    .when(fast).send(any());

            peers.add(slow);
            peers.add(fast);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var slowRequest = unknownTransactions(1_000, 10_000);
                slowIncoming.get().onMessage(slow, BitcoinMessages.getData(new GetDataMessage(slowRequest)));
                assertTrue(slowSendEntered.await(5, TimeUnit.SECONDS), "Slow peer never entered send()");

                var fastRequest = unknownTransactions(1, 20_000);
                fastIncoming.get().onMessage(fast, BitcoinMessages.getData(new GetDataMessage(fastRequest)));

                BitcoinMessage fastResponse = fastOutbound.poll(2, TimeUnit.SECONDS);
                assertNotNull(fastResponse, "Slow peer must not block GETDATA service for another peer");
                assertEquals("notfound", fastResponse.command());
                assertEquals(fastRequest, BitcoinMessages.decodeNotFound(fastResponse).inventory());

                releaseSlowSend.countDown();
            } finally {
                releaseSlowSend.countDown();
            }
        }
    }

    @Test
    void blockRelayOnlyPeerRejectsTransactionRelayButStillServesHeaders()
            throws Exception {

        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("block-relay-only-role"));
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

            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> {
                incoming.set(invocation.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());

            var outbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> {
                outbound.add(invocation.getArgument(0));
                return null;
            }).when(peer).send(any());

            peers.add(peer, PeerConnectionRole.BLOCK_RELAY_ONLY);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                Hash256 unknownTx = Hash256.fromDisplayHex("42".repeat(32));
                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.inv(new InvMessage(List.of(
                                new InventoryVector(InventoryVector.MSG_TX, unknownTx)
                        )))
                );

                Hash256 genesis = parameters.genesisBlockHash();
                incoming.get().onMessage(
                        peer,
                        BitcoinMessages.getHeaders(new GetHeadersMessage(
                                VersionMessage.CURRENT_PROTOCOL_VERSION,
                                List.of(genesis),
                                new Hash256(new byte[32])
                        ))
                );

                BitcoinMessage response = take(outbound);
                assertEquals("headers", response.command(),
                        "BLOCK_RELAY_ONLY peer must retain block/header relay");
                assertTrue(outbound.isEmpty(),
                        "BLOCK_RELAY_ONLY peer must not trigger transaction GETDATA");
            }

            verify(peer).removeMessageListener(incoming.get());
        }
    }


    @Test
    void largeInventoryIsRetainedBeyondFirst128AndPeerWindowRefills() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("tx-request-window"));
             var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { incoming.set(invocation.getArgument(0)); return null; })
                    .when(peer).addMessageListener(any());
            var outbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { outbound.add(invocation.getArgument(0)); return null; })
                    .when(peer).send(any());
            peers.add(peer);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var inventory = unknownTransactions(1_500, 30_000);
                incoming.get().onMessage(peer, BitcoinMessages.inv(new InvMessage(inventory)));

                List<InventoryVector> requested = new ArrayList<>();
                while (requested.size() < 128) {
                    BitcoinMessage message = take(outbound);
                    assertEquals("getdata", message.command());
                    var batch = BitcoinMessages.decodeGetData(message).inventory();
                    assertTrue(batch.size() <= 128);
                    requested.addAll(batch);
                }
                assertEquals(128, requested.size());
                assertEquals(inventory.subList(0, 128).stream().map(InventoryVector::hash).toList(),
                        requested.stream().map(InventoryVector::hash).toList());

                incoming.get().onMessage(peer, BitcoinMessages.notFound(
                        new NotFoundMessage(List.of(requested.getFirst()))));
                BitcoinMessage refill = take(outbound);
                assertEquals("getdata", refill.command());
                assertEquals(inventory.get(128).hash(),
                        BitcoinMessages.decodeGetData(refill).inventory().getFirst().hash());
            }
        }
    }

    @Test
    void notFoundRetriesTransactionFromAlternativeAnnouncingPeer() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("tx-request-failover"));
             var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var first = mock(Peer.class);
            var second = mock(Peer.class);
            when(first.isReady()).thenReturn(true);
            when(second.isReady()).thenReturn(true);
            var firstIncoming = new AtomicReference<PeerMessageListener>();
            var secondIncoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { firstIncoming.set(invocation.getArgument(0)); return null; })
                    .when(first).addMessageListener(any());
            doAnswer(invocation -> { secondIncoming.set(invocation.getArgument(0)); return null; })
                    .when(second).addMessageListener(any());
            var firstOutbound = new LinkedBlockingQueue<BitcoinMessage>();
            var secondOutbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { firstOutbound.add(invocation.getArgument(0)); return null; }).when(first).send(any());
            doAnswer(invocation -> { secondOutbound.add(invocation.getArgument(0)); return null; }).when(second).send(any());
            peers.add(first);
            peers.add(second);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var vector = unknownTransactions(1, 40_000).getFirst();
                firstIncoming.get().onMessage(first, BitcoinMessages.inv(new InvMessage(List.of(vector))));
                assertEquals(vector.hash(), BitcoinMessages.decodeGetData(take(firstOutbound)).inventory().getFirst().hash());

                secondIncoming.get().onMessage(second, BitcoinMessages.inv(new InvMessage(List.of(vector))));
                assertNull(secondOutbound.poll(250, TimeUnit.MILLISECONDS),
                        "A transaction must not be requested from two peers concurrently");

                firstIncoming.get().onMessage(first, BitcoinMessages.notFound(new NotFoundMessage(List.of(vector))));
                assertEquals(vector.hash(), BitcoinMessages.decodeGetData(take(secondOutbound)).inventory().getFirst().hash());
            }
        }
    }


    private static List<InventoryVector> unknownTransactions(int count, int seed) {
        List<InventoryVector> inventory = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] hash = new byte[32];
            int value = seed + i;
            hash[0] = (byte) value;
            hash[1] = (byte) (value >>> 8);
            hash[2] = (byte) (value >>> 16);
            hash[3] = (byte) (value >>> 24);
            inventory.add(new InventoryVector(InventoryVector.MSG_TX, new Hash256(hash)));
        }
        return List.copyOf(inventory);
    }

    private static BitcoinMessage take(BlockingQueue<BitcoinMessage> queue) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                fail("Expected network message");
            }

            BitcoinMessage message =
                    queue.poll(remaining, TimeUnit.NANOSECONDS);

            assertNotNull(message, "Expected network message");

            /*
             * Periodic BIP133 advertisement is independent control traffic and
             * must not disturb tests that are specifically asserting another
             * relay response.
             */
            if (!"feefilter".equals(message.command())) {
                return message;
            }
        }
    }
    @Test
    void relaysConnectedBlockOnceAndExcludesSourcePeer() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("connected-block-relay"));
             var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var source = mock(Peer.class);
            var destination = mock(Peer.class);
            when(source.isReady()).thenReturn(true);
            when(destination.isReady()).thenReturn(true);
            peers.add(source);
            peers.add(destination);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                Block block = mock(Block.class);
                Hash256 hash = Hash256.fromDisplayHex("42".repeat(32));
                when(block.hash()).thenReturn(hash);

                relay.relayConnectedBlock(block, source);
                relay.relayConnectedBlock(block, source);

                verify(source, never()).send(any());
                verify(destination, timeout(2_000).times(1)).send(argThat(message -> {
                    if (!"inv".equals(message.command())) return false;
                    var inventory = BitcoinMessages.decodeInv(message).inventory();
                    return inventory.size() == 1
                            && inventory.getFirst().type() == InventoryVector.MSG_BLOCK
                            && inventory.getFirst().hash().equals(hash);
                }));
            }
        }
    }

    private static Transaction spend(OutPoint point, long value, byte[] script) {
        return new Transaction(
                2,
                List.of(new TxIn(point, new byte[]{1, 0x51}, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value, script)),
                new UInt32(0)
        );
    }
}
