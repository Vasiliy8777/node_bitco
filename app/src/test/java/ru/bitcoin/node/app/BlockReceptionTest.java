package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.*;
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

class BlockReceptionTest {
    @TempDir Path directory;

    @Test void servesValidatedFormerTipInLegacyWitnessAndCompactForms() throws Exception {
        try (var fork = fork()) {
            fork.main.processBlock(fork.side); fork.main.processBlock(fork.sideChild);
            fork.branch.processBlock(fork.sideChild);
            fork.main.processBlock(mine(fork.branch, (byte) 0x52));
            var replies = requestBlocks(fork.main, fork.mainDb, List.of(
                    new InventoryVector(InventoryVector.MSG_BLOCK, fork.active.hash()),
                    new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, fork.active.hash()),
                    new InventoryVector(InventoryVector.MSG_CMPCT_BLOCK, fork.active.hash())));
            assertEquals(List.of("block", "block", "cmpctblock"), replies.stream().map(BitcoinMessage::command).toList());
            assertArrayEquals(ru.bitcoin.node.protocol.serialization.BlockSerializer.serializeLegacy(fork.active), replies.get(0).payload());
            assertArrayEquals(ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(fork.active), replies.get(1).payload());
            assertEquals(fork.active.header(), BitcoinMessages.decodeCompactBlock(replies.get(2), 2).header());
        }
    }

    @Test void ignoresUnknownUnvalidatedPrunedAndUnsupportedBlockRequests() throws Exception {
        try (var fork = fork()) {
            fork.main.processBlock(fork.side);
            new ru.bitcoin.node.storage.block.RocksDbBlockStore(fork.mainDb).delete(fork.active.hash());
            assertTrue(fork.main.requestedHeader(fork.active.hash()).isPresent());
            assertTrue(fork.main.requestedBlock(fork.active.hash()).isEmpty());
            var unknown = Hash256.fromDisplayHex("35".repeat(32));
            assertEquals(List.of(), requestBlocks(fork.main, fork.mainDb, List.of(
                    new InventoryVector(InventoryVector.MSG_BLOCK, unknown),
                    new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, fork.side.hash()),
                    new InventoryVector(InventoryVector.MSG_CMPCT_BLOCK, fork.active.hash()),
                    new InventoryVector(99, unknown))));
        }
    }

    @Test void staleTipFallsBackToFullWitnessBlockForCompactRequests() throws Exception {
        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_600_000_000L, new Mempool());
            var block = mine(validation, (byte) 0x51); validation.processBlock(block);
            var replies = requestBlocks(validation, db, List.of(new InventoryVector(InventoryVector.MSG_CMPCT_BLOCK, block.hash())));
            assertEquals(1, replies.size());
            assertEquals("block", replies.getFirst().command());
            assertArrayEquals(ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(block), replies.getFirst().payload());
        }
    }

    private static List<BitcoinMessage> requestBlocks(NodeValidationService validation, RocksDbDatabase db,
                                                     List<InventoryVector> inventory) throws Exception {
        try (var peers = new PeerManager()) {
            var peer = mock(Peer.class); when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                    VersionMessage.DEFAULT_SERVICES, 0, NetworkAddress.unspecified(), NetworkAddress.unspecified(),
                    1, "/block-serving-test/", 3, true));
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            var outgoing = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer);
            var sync = new NodeSyncInfrastructure(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var missingTx = new InventoryVector(InventoryVector.MSG_TX, Hash256.fromDisplayHex("47".repeat(32)));
                var vectors = new ArrayList<>(inventory); vectors.add(missingTx);
                incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(vectors)));
                var replies = new ArrayList<BitcoinMessage>();
                while (true) {
                    var message = outgoing.poll(5, TimeUnit.SECONDS); assertNotNull(message);
                    if ("notfound".equals(message.command())) {
                        assertEquals(List.of(missingTx), BitcoinMessages.decodeNotFound(message).inventory());
                        break;
                    }
                    replies.add(message);
                }
                verify(peer, never()).close();
                return List.copyOf(replies);
            }
        }
    }

    @Test void directHeaderRequestsAllowValidatedFormerTipsButNotUnvalidatedOrFailedBranches() throws Exception {
        try (var fork = fork()) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            assertTrue(fork.main.requestedHeader(fork.active.hash()).isPresent());
            fork.main.processHeaders(sync.headerSyncService(), new HeadersMessage(List.of(fork.side.header())));
            assertTrue(fork.main.requestedHeader(fork.side.hash()).isEmpty(), "Header validation alone is insufficient");
            assertTrue(fork.main.requestedBlock(fork.side.hash()).isEmpty());
            fork.main.processBlock(fork.side);
            assertTrue(fork.main.requestedHeader(fork.side.hash()).isEmpty(), "Stored side-chain body has no script-validation credit");
            assertTrue(fork.main.requestedBlock(fork.side.hash()).isEmpty());
            fork.main.processBlock(fork.sideChild);
            fork.branch.processBlock(fork.sideChild);
            fork.main.processBlock(mine(fork.branch, (byte) 0x52));
            assertTrue(fork.main.activeBlockDepth(fork.active.hash()).isEmpty(), "Old tip must now be on a side branch");
            assertEquals(fork.active.header(), fork.main.requestedHeader(fork.active.hash()).orElseThrow());
            assertTrue(fork.main.findBlock(fork.active.hash()).isEmpty(), "Active-chain lookup must retain its semantics");
            assertEquals(fork.active.hash(), fork.main.requestedBlock(fork.active.hash()).orElseThrow().block().hash());
            fork.main.invalidateBlock(fork.active.hash());
            assertTrue(fork.main.requestedHeader(fork.active.hash()).isEmpty(), "Failed branches cannot be served");
            assertTrue(fork.main.requestedBlock(fork.active.hash()).isEmpty());
            assertTrue(fork.main.requestedHeader(Hash256.fromDisplayHex("35".repeat(32))).isEmpty());
        }
    }

    @Test void individuallyValidHeadersFromDifferentBranchesCannotFormOneResponse() throws Exception {
        try (var fork = fork()) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            var next = mine(fork.main, (byte) 0x51).header();
            var response = new HeadersMessage(List.of(fork.side.header(), next));
            var observed = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> sync.headerSyncService().processWithReception(
                    response, index -> observed.incrementAndGet()));
            assertEquals(0, observed.get());
            assertNull(sync.blockIndexLookup().find(fork.side.hash()));
            assertNull(sync.blockIndexLookup().find(next.hash()));
            assertTrue(sync.headerSyncService().processWithReception(
                    new HeadersMessage(List.of(fork.side.header(), fork.sideChild.header())), ignored -> { }).lastHeaderNew());
        }
    }

    @Test void competingSyncAndRelayReceiversCreditANewHeaderExactlyOnce() throws Exception {
        try (var fork = fork(); var executor = Executors.newFixedThreadPool(2)) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            var message = new HeadersMessage(List.of(mine(fork.main, (byte) 0x51).header()));
            var start = new CountDownLatch(1);
            var synchronous = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return sync.headerSyncService().processWithReception(message, ignored -> { });
            });
            var relay = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return fork.main.processHeadersWithReception(sync.headerSyncService(), message);
            });
            start.countDown();
            var first = synchronous.get(5, TimeUnit.SECONDS);
            var second = relay.get(5, TimeUnit.SECONDS);
            assertEquals(1, (first.lastHeaderNew() ? 1 : 0) + (second.lastHeaderNew() ? 1 : 0));
            assertEquals(first.indexes(), second.indexes());
            assertFalse(sync.headerSyncService().processWithReception(message, ignored -> { }).lastHeaderNew());
        }
    }

    @Test void failedHeaderBatchDoesNotPersistOrCreditItsValidPrefix() throws Exception {
        try (var fork = fork()) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            var valid = mine(fork.main, (byte) 0x51).header();
            var invalid = new ru.bitcoin.node.protocol.block.BlockHeader(valid.version(), valid.hash(),
                    valid.merkleRoot(), valid.timestamp(), new ru.bitcoin.node.common.types.UInt32(0), valid.nonce());
            assertThrows(RuntimeException.class, () -> sync.headerSyncService().processWithReception(
                    new HeadersMessage(List.of(valid, invalid)), ignored -> { }));
            assertNull(sync.blockIndexLookup().find(valid.hash()));
            assertTrue(sync.headerSyncService().processWithReception(new HeadersMessage(List.of(valid)), ignored -> { }).lastHeaderNew());
            assertFalse(sync.headerSyncService().processWithReception(new HeadersMessage(List.of()), ignored -> { }).lastHeaderNew());
        }
    }

    @Test void distinguishesNewSideChainBodyFromKnownHeaderAndDuplicateBody() throws Exception {
        try (var fork = fork()) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            fork.main.processHeaders(sync.headerSyncService(), new HeadersMessage(List.of(fork.side.header())));
            var first = fork.main.processBlockWithReception(fork.side);
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, first.result());
            assertTrue(first.newBody());
            assertFalse(fork.main.processBlockWithReception(fork.side).newBody());
            assertEquals(fork.active.hash(), fork.main.downloadTip().hash());
            assertFalse(fork.main.processBlockWithReception(fork.active).newBody());
        }
    }

    @Test void unknownParentsAndMalformedBodiesDoNotCreateReceptionCredit() throws Exception {
        try (var fork = fork()) {
            var orphan = fork.main.processBlockWithReception(fork.sideChild);
            assertEquals(BlockProcessingResult.UNKNOWN_PARENT, orphan.result());
            assertFalse(orphan.newBody());
            assertThrows(ru.bitcoin.node.consensus.block.BlockValidationException.class,
                    () -> fork.main.processBlockWithReception(new Block(fork.side.header(), List.of())));
            assertTrue(fork.main.findBlock(fork.side.hash()).isEmpty());
            assertTrue(fork.main.processBlockWithReception(fork.side).newBody());
        }
    }

    @Test void simultaneousDeliveriesProduceExactlyOneNewBodyReceipt() throws Exception {
        try (var fork = fork(); var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<NodeValidationService.BlockReception> receive = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return fork.main.processBlockWithReception(fork.side);
            };
            var first = executor.submit(receive); var second = executor.submit(receive);
            start.countDown();
            int credits = (first.get(5, TimeUnit.SECONDS).newBody() ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS).newBody() ? 1 : 0);
            assertEquals(1, credits);
        }
    }

    @Test void batchReceiptsCreditNewSideChainBodiesWithoutCreditingReplays() throws Exception {
        try (var fork = fork()) {
            var receipts = fork.main.processInitialSyncBatchWithReception(List.of(fork.side, fork.side, fork.sideChild, fork.active));
            assertEquals(List.of(true, false, true, false), receipts.stream().map(NodeValidationService.BlockReception::newBody).toList());
            assertEquals(fork.active.hash(), fork.main.downloadTip().hash());
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, receipts.get(0).result());
        }
    }

    @Test void compactSideChainBodyCreditsSourceOnceWithoutActiveTipProgress() throws Exception { relaySideBody(false); }
    @Test void fullFallbackSideChainBodyCreditsSourceOnceWithoutActiveTipProgress() throws Exception { relaySideBody(true); }

    private void relaySideBody(boolean fallback) throws Exception {
        try (var fork = fork(); var peers = new PeerManager()) {
            var sync = new NodeSyncInfrastructure(fork.mainDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
            var peer = mock(Peer.class); when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                    VersionMessage.DEFAULT_SERVICES, 0, NetworkAddress.unspecified(), NetworkAddress.unspecified(), 1, "/body-credit-test/", 2, true));
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            var outgoing = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(call -> { outgoing.add(call.getArgument(0)); return null; }).when(peer).send(any());
            peers.add(peer);
            try (var relay = new NodeRelayService(fork.main, sync, peers)) {
                incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(false, 2)));
                CompactBlockMessage compact;
                if (fallback) {
                    var coinbase = fork.side.transactions().getFirst();
                    var wrong = new ru.bitcoin.node.protocol.transaction.Transaction(coinbase.version(), coinbase.inputs(),
                            List.of(new ru.bitcoin.node.protocol.transaction.TxOut(1, new byte[]{0x51})), coinbase.lockTime());
                    compact = new CompactBlockMessage(fork.side.header(), 42, List.of(), List.of(new PrefilledTransaction(0, wrong)));
                } else compact = CompactBlockFactory.create(fork.side, 42, 2);
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(compact, 2));
                if (fallback) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    BitcoinMessage request;
                    do {
                        request = outgoing.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        assertNotNull(request);
                    } while (!"getdata".equals(request.command()));
                    incoming.get().onMessage(peer, new BitcoinMessage("block",
                            ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize(fork.side)));
                }
                verify(peer, timeout(3000).times(1)).recordAcceptedBlock();
                incoming.get().onMessage(peer, BitcoinMessages.compactBlock(CompactBlockFactory.create(fork.side, 42, 2), 2));
                // A queued request/response provides a barrier after the duplicate.
                incoming.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION, List.of(fork.active.hash()), fork.active.hash())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                BitcoinMessage response;
                do {
                    response = outgoing.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    assertNotNull(response);
                } while (!"headers".equals(response.command()));
                verify(peer, times(1)).recordAcceptedBlock();
                assertEquals(fork.active.hash(), fork.main.downloadTip().hash());
                assertEquals(0, relay.outboundRecoveryView().lastTipUpdateSeconds(), "Side-chain storage is not active-tip progress");
            }
        }
    }

    private Fork fork() throws Exception {
        var mainDb = new RocksDbDatabase(directory.resolve("main"));
        var branchDb = new RocksDbDatabase(directory.resolve("branch"));
        var parameters = NetworkParametersRegistry.regtest();
        var main = new NodeValidationService(mainDb, parameters, () -> 1_800_000_000L, new Mempool());
        // The shared template miner uses a fixed payout, so separate timestamps
        // ensure this fixture actually creates a competing branch.
        var branch = new NodeValidationService(branchDb, parameters, () -> 1_800_000_100L, new Mempool());
        main.processBlock(mine(main, (byte) 0x51));
        Block active = mine(main, (byte) 0x51); main.processBlock(active);
        Block side = mine(branch, (byte) 0x52); branch.processBlock(side);
        assertNotEquals(active.header().previousBlockHash(), side.hash(), "Fixture must contain distinct height-one blocks");
        return new Fork(mainDb, branchDb, main, branch, active, side, mine(branch, (byte) 0x52));
    }

    private static Block mine(NodeValidationService validation, byte payout) throws Exception {
        var mining = new MiningController(validation, null, NetworkParametersRegistry.regtest(), () -> true,
                new byte[]{payout}, 4_000_000, new FeeRate(0));
        return MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
    }
    private record Fork(RocksDbDatabase mainDb, RocksDbDatabase branchDb, NodeValidationService main, NodeValidationService branch,
                        Block active, Block side, Block sideChild) implements AutoCloseable {
        public void close() { branchDb.close(); mainDb.close(); }
    }
}
