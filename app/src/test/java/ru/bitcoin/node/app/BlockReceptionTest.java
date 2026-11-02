package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockProcessingResult;
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
        return new Fork(mainDb, branchDb, main, active, side, mine(branch, (byte) 0x52));
    }

    private static Block mine(NodeValidationService validation, byte payout) throws Exception {
        var mining = new MiningController(validation, null, NetworkParametersRegistry.regtest(), () -> true,
                new byte[]{payout}, 4_000_000, new FeeRate(0));
        return MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
    }
    private record Fork(RocksDbDatabase mainDb, RocksDbDatabase branchDb, NodeValidationService main,
                        Block active, Block side, Block sideChild) implements AutoCloseable {
        public void close() { branchDb.close(); mainDb.close(); }
    }
}
