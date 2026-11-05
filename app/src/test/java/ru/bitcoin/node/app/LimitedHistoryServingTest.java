package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
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

class LimitedHistoryServingTest {
    @TempDir Path directory;
    private static final long LIMITED = VersionMessage.NODE_WITNESS | VersionMessage.NODE_NETWORK_LIMITED;

    @Test void blockRequestsCloseOnlyOutsideAdvertisedLimitedWindow() throws Exception {
        try (var fixture = new Fixture()) {
            var boundary = new Endpoint(fixture.peers, LIMITED);
            boundary.data(InventoryVector.MSG_WITNESS_BLOCK, fixture.hashes.get(1));
            assertEquals(fixture.hashes.get(1), BlockParser.parse(boundary.take().payload()).hash());
            verify(boundary.peer, never()).close();
            for (long type : List.of(InventoryVector.MSG_BLOCK, InventoryVector.MSG_WITNESS_BLOCK, InventoryVector.MSG_CMPCT_BLOCK)) {
                var denied = new Endpoint(fixture.peers, LIMITED);
                denied.data(type, fixture.hashes.getFirst());
                denied.assertClosedWithoutPenalty();
            }
            var full = new Endpoint(fixture.peers, LIMITED | VersionMessage.NODE_NETWORK);
            full.data(InventoryVector.MSG_WITNESS_BLOCK, fixture.hashes.getFirst());
            assertEquals(fixture.hashes.getFirst(), BlockParser.parse(full.take().payload()).hash());
            verify(full.peer, never()).close();
        }
    }

    @Test void privacyCheckPrecedesBodyAvailabilityButUnknownHashesAreIgnored() throws Exception {
        try (var fixture = new Fixture()) {
            new RocksDbBlockStore(fixture.db).delete(fixture.hashes.getFirst());
            var denied = new Endpoint(fixture.peers, LIMITED);
            denied.data(InventoryVector.MSG_WITNESS_BLOCK, fixture.hashes.getFirst());
            denied.assertClosedWithoutPenalty();
            var unknown = new Endpoint(fixture.peers, LIMITED);
            unknown.data(InventoryVector.MSG_WITNESS_BLOCK, Hash256.fromDisplayHex("31".repeat(32)));
            unknown.barrier(); unknown.takeBarrier(); verify(unknown.peer, never()).close();
            var prunedBlockTxn = new Endpoint(fixture.peers, LIMITED);
            prunedBlockTxn.blockTxn(fixture.hashes.getFirst());
            prunedBlockTxn.barrier(); prunedBlockTxn.takeBarrier(); verify(prunedBlockTxn.peer, never()).close();
        }
    }

    @Test void oldBlockTxnFallbackUsesWindowWhileRecentBlockTxnStillWorks() throws Exception {
        try (var fixture = new Fixture()) {
            var denied = new Endpoint(fixture.peers, LIMITED);
            denied.blockTxn(fixture.hashes.getFirst()); denied.assertClosedWithoutPenalty();
            var allowed = new Endpoint(fixture.peers, LIMITED);
            allowed.blockTxn(fixture.hashes.get(1)); assertEquals("block", allowed.take().command());
            allowed.blockTxn(fixture.hashes.getLast());
            assertEquals(fixture.hashes.getLast(), BitcoinMessages.decodeBlockTxn(allowed.take(), 2).blockHash());
            verify(allowed.peer, never()).close();
        }
    }

    @Test void noBanCanServeAvailableOldBodiesButDownloadAloneCannot() throws Exception {
        try (var fixture = new Fixture()) {
            var old = fixture.hashes.getFirst();
            for (long type : List.of(InventoryVector.MSG_BLOCK, InventoryVector.MSG_WITNESS_BLOCK, InventoryVector.MSG_CMPCT_BLOCK)) {
                var trusted = new Endpoint(fixture.peers, LIMITED, PeerPermission.NOBAN, PeerPermission.DOWNLOAD);
                trusted.data(type, old);
                assertEquals(old, BlockParser.parse(trusted.take().payload()).hash());
                verify(trusted.peer, never()).close();
            }
            var trusted = new Endpoint(fixture.peers, LIMITED, PeerPermission.NOBAN);
            trusted.blockTxn(old); assertEquals("block", trusted.take().command());
            var download = new Endpoint(fixture.peers, LIMITED, PeerPermission.DOWNLOAD);
            download.data(InventoryVector.MSG_WITNESS_BLOCK, old); download.assertClosedWithoutPenalty();
            new RocksDbBlockStore(fixture.db).delete(old);
            var pruned = new Endpoint(fixture.peers, LIMITED, PeerPermission.NOBAN);
            pruned.data(InventoryVector.MSG_WITNESS_BLOCK, old); pruned.blockTxn(old);
            pruned.barrier(); pruned.takeBarrier(); verify(pruned.peer, never()).close();
        }
    }

    private final class Fixture implements AutoCloseable {
        final RocksDbDatabase db = new RocksDbDatabase(directory);
        final NodeValidationService validation = new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
        final PeerManager peers = new PeerManager();
        final List<Hash256> hashes = new ArrayList<>();
        final NodeRelayService relay;
        Fixture() throws Exception {
            var mining = new MiningController(validation, null, NetworkParametersRegistry.regtest(), () -> true,
                    new byte[]{0x51}, 4_000_000, new FeeRate(0));
            for (int i = 0; i < 292; i++) {
                var block = MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
                validation.processBlock(block); hashes.add(block.hash());
            }
            relay = new NodeRelayService(validation, new NodeSyncInfrastructure(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L), peers);
        }
        public void close() throws Exception { try { relay.close(); peers.close(); } finally { db.close(); } }
    }

    private static final class Endpoint {
        final Peer peer = mock(Peer.class);
        final AtomicReference<PeerMessageListener> incoming = new AtomicReference<>();
        final LinkedBlockingQueue<BitcoinMessage> outgoing = new LinkedBlockingQueue<>();
        final InventoryVector missingTx = new InventoryVector(InventoryVector.MSG_TX, Hash256.fromDisplayHex("47".repeat(32)));
        Endpoint(PeerManager peers, long services, PeerPermission... permissions) throws Exception {
            for (var permission : permissions) when(peer.hasPermission(permission)).thenReturn(true);
            when(peer.isReady()).thenReturn(true); when(peer.localServices()).thenReturn(services);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(70016, VersionMessage.DEFAULT_SERVICES, 0,
                    NetworkAddress.unspecified(), NetworkAddress.unspecified(), 1, "/limited-serving-test/", 292, true));
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer);
        }
        void data(long type, Hash256 hash) throws Exception { incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(List.of(new InventoryVector(type, hash))))); }
        void blockTxn(Hash256 hash) throws Exception { incoming.get().onMessage(peer, BitcoinMessages.getBlockTxn(new BlockTransactionsRequest(hash, List.of(0)))); }
        void barrier() throws Exception { incoming.get().onMessage(peer, BitcoinMessages.getData(new GetDataMessage(List.of(missingTx)))); }
        void takeBarrier() throws Exception { assertEquals(List.of(missingTx), BitcoinMessages.decodeNotFound(take()).inventory()); }
        BitcoinMessage take() throws Exception { var message = outgoing.poll(5, TimeUnit.SECONDS); assertNotNull(message); return message; }
        void assertClosedWithoutPenalty() throws Exception {
            verify(peer, timeout(3000)).close(); verify(peer, never()).disconnectForProtocolViolation(anyString(), any());
            assertTrue(outgoing.isEmpty());
        }
    }
}
