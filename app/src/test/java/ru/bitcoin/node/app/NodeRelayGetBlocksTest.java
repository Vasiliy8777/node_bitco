package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockPruner;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayGetBlocksTest {
    @TempDir Path directory;
    private static final Hash256 ZERO = new Hash256(new byte[32]);

    @Test void fullPagesHavePerPeerOneShotContinuationEvenAfterAShortPage() throws Exception {
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = validation(db, 0);
            var hashes = grow(validation, 502);
            var genesis = NetworkParametersRegistry.regtest().genesisBlockHash();
            assertEquals(List.of(hashes.get(0)), validation.blockInventory(List.of(), hashes.get(1)).hashes());
            assertEquals(List.of(), validation.blockInventory(List.of(genesis), hashes.get(0)).hashes());
            assertTrue(validation.blockInventory(List.of(genesis), ZERO).reachedLimit());
            var first = new Endpoint(peers); var second = new Endpoint(peers);
            try (var relay = new NodeRelayService(validation, infrastructure(db), peers)) {
                first.getBlocks(List.of(), hashes.get(1));
                assertEquals(List.of(hashes.get(0)), invHashes(first.take()));
                first.getBlocks(List.of(genesis), ZERO);
                assertEquals(hashes.subList(0, 500), invHashes(first.take()));
                first.getBlocks(List.of(hashes.get(499)), ZERO);
                assertEquals(hashes.subList(500, 502), invHashes(first.take()));
                second.getData(hashes.get(499)); second.barrier();
                assertBlock(second.take(), hashes.get(499)); second.takeBarrier();
                first.getData(hashes.get(499)); first.getData(hashes.get(499)); first.barrier();
                assertBlock(first.take(), hashes.get(499));
                assertEquals(List.of(hashes.getLast()), invHashes(first.take()));
                assertBlock(first.take(), hashes.get(499)); first.takeBarrier();
                assertTrue(first.outgoing.isEmpty());

                // A newly full page replaces the continuation marker. The old
                // GETBLOCKTXN fallback must use the same block-serving hook.
                first.getBlocks(List.of(genesis), ZERO); assertEquals(500, invHashes(first.take()).size());
                grow(validation, 512);
                first.incoming.get().onMessage(first.peer, BitcoinMessages.getBlockTxn(
                        new BlockTransactionsRequest(hashes.get(499), List.of(Integer.MAX_VALUE))));
                assertBlock(first.take(), hashes.get(499));
                assertEquals(List.of(validation.activeTip().hash()), invHashes(first.take()));
                verify(first.peer, never()).close(); verify(second.peer, never()).close();
            }
        }
    }

    @Test void pruningStopsAtTheAvailabilityAndOneHourRetentionBoundaries() throws Exception {
        try (var db = new RocksDbDatabase(directory)) {
            var validation = validation(db, BlockPruner.MANUAL_ONLY);
            var hashes = grow(validation, 300);
            var genesis = NetworkParametersRegistry.regtest().genesisBlockHash();
            assertEquals(List.of(), validation.blockInventory(List.of(genesis), ZERO).hashes());
            assertEquals(List.of(), validation.blockInventory(List.of(hashes.get(16)), ZERO).hashes());
            assertEquals(hashes.subList(18, 300), validation.blockInventory(List.of(hashes.get(17)), ZERO).hashes());
            new RocksDbBlockStore(db).delete(hashes.get(19));
            assertEquals(List.of(hashes.get(18)), validation.blockInventory(List.of(hashes.get(17)), ZERO).hashes());
        }
    }

    @Test void emptyInventoryIsSilentAndOversizedLocatorsCloseWithoutProtocolPenalty() throws Exception {
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = validation(db, 0); var endpoint = new Endpoint(peers);
            try (var relay = new NodeRelayService(validation, infrastructure(db), peers)) {
                endpoint.getBlocks(List.of(), ZERO); endpoint.barrier(); endpoint.takeBarrier();
                assertTrue(endpoint.outgoing.isEmpty());
                byte[] oversized = new byte[37]; oversized[4] = 102;
                endpoint.incoming.get().onMessage(endpoint.peer, new BitcoinMessage("getblocks", oversized));
                verify(endpoint.peer, timeout(3000)).close();
                verify(endpoint.peer, never()).disconnectForProtocolViolation(anyString(), any());
            }
        }
    }

    private static NodeValidationService validation(RocksDbDatabase db, long pruning) {
        return new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool(), pruning);
    }
    private static NodeSyncInfrastructure infrastructure(RocksDbDatabase db) {
        return new NodeSyncInfrastructure(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L);
    }
    private static List<Hash256> grow(NodeValidationService validation, int height) throws Exception {
        var hashes = new ArrayList<Hash256>();
        var mining = new MiningController(validation, null, NetworkParametersRegistry.regtest(), () -> true,
                new byte[]{0x51}, 4_000_000, new FeeRate(0));
        while (validation.activeTip().height() < height) {
            Block block = MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
            validation.processBlock(block); hashes.add(block.hash());
        }
        return hashes;
    }
    private static List<Hash256> invHashes(BitcoinMessage message) throws Exception {
        assertEquals("inv", message.command());
        var vectors = BitcoinMessages.decodeInv(message).inventory();
        assertTrue(vectors.stream().allMatch(vector -> vector.type() == InventoryVector.MSG_BLOCK));
        return vectors.stream().map(InventoryVector::hash).toList();
    }
    private static void assertBlock(BitcoinMessage message, Hash256 hash) {
        assertEquals("block", message.command()); assertEquals(hash, BlockParser.parse(message.payload()).hash());
    }

    private static final class Endpoint {
        final Peer peer = mock(Peer.class);
        final AtomicReference<PeerMessageListener> incoming = new AtomicReference<>();
        final LinkedBlockingQueue<BitcoinMessage> outgoing = new LinkedBlockingQueue<>();
        final InventoryVector missingTx = new InventoryVector(InventoryVector.MSG_TX, Hash256.fromDisplayHex("47".repeat(32)));
        Endpoint(PeerManager peers) throws Exception {
            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(70016, VersionMessage.DEFAULT_SERVICES,
                    0, NetworkAddress.unspecified(), NetworkAddress.unspecified(), 1, "/getblocks-test/", 0, true));
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer);
        }
        void getBlocks(List<Hash256> locator, Hash256 stop) throws Exception {
            incoming.get().onMessage(peer, BitcoinMessages.getBlocks(new GetBlocksMessage(70016, locator, stop)));
        }
        void getData(Hash256 hash) throws Exception {
            incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(List.of(new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, hash)))));
        }
        void barrier() throws Exception { incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(List.of(missingTx)))); }
        void takeBarrier() throws Exception { assertEquals(List.of(missingTx), BitcoinMessages.decodeNotFound(take()).inventory()); }
        BitcoinMessage take() throws Exception { var message = outgoing.poll(10, TimeUnit.SECONDS); assertNotNull(message); return message; }
    }
}
