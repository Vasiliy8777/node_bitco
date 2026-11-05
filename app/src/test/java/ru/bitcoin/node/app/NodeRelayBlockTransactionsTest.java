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
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayBlockTransactionsTest {
    @TempDir Path directory;

    @Test void recentSideBodyUsesHeightBoundaryAndOldSideBodyUsesOrdinaryEligibility() throws Exception {
        try (var fixture = new Fixture(); var branchDb = new RocksDbDatabase(directory.resolve("branch"))) {
            var branch = new NodeValidationService(branchDb, NetworkParametersRegistry.regtest(), () -> 1_800_000_100L, new Mempool());
            var side = mine(branch); fixture.validation.processBlock(side);
            assertTrue(fixture.validation.requestedBlock(side.hash()).isEmpty(), "Fixture must lack side-chain script validation");
            fixture.growTo(11);
            fixture.request(side.hash(), List.of(0));
            var response = fixture.take();
            assertEquals("blocktxn", response.command());
            var transactions = BitcoinMessages.decodeBlockTxn(response, 2);
            assertEquals(side.hash(), transactions.blockHash());
            assertArrayEquals(TransactionSerializer.serialize(side.transactions().getFirst()),
                    TransactionSerializer.serialize(transactions.transactions().getFirst()));
            fixture.growTo(12);
            fixture.request(side.hash(), List.of(0));
            fixture.request(fixture.first.hash(), List.of(Integer.MAX_VALUE));
            var fallback = fixture.take();
            assertEquals("block", fallback.command());
            assertArrayEquals(BlockSerializer.serialize(fixture.first), fallback.payload());
            assertTrue(fixture.outgoing.isEmpty(), "Old ineligible side body must be silently ignored");
            verify(fixture.peer, never()).close();
        }
    }

    @Test void unknownAndPrunedBodiesAreIgnoredWithoutNotFound() throws Exception {
        try (var fixture = new Fixture()) {
            var second = mine(fixture.validation); fixture.validation.processBlock(second);
            new RocksDbBlockStore(fixture.db).delete(second.hash());
            fixture.request(second.hash(), List.of(0));
            fixture.request(Hash256.fromDisplayHex("31".repeat(32)), List.of(0));
            fixture.request(fixture.first.hash(), List.of(0));
            var response = fixture.take();
            assertEquals("blocktxn", response.command());
            assertEquals(fixture.first.hash(), BitcoinMessages.decodeBlockTxn(response, 2).blockHash());
            assertTrue(fixture.outgoing.isEmpty());
            verify(fixture.peer, never()).close();
        }
    }

    @Test void emptyIndexesDisconnectEvenForUnknownHash() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.request(Hash256.fromDisplayHex("31".repeat(32)), List.of());
            verify(fixture.peer, timeout(3000)).close();
            verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            assertTrue(fixture.outgoing.isEmpty());
        }
    }

    @Test void cachedRecentBlockBypassesDepthFallbackAndIsReplacedByNextBlock() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.relay.relayConnectedBlock(fixture.first, fixture.peer);
            fixture.growTo(12);
            fixture.request(fixture.first.hash(), List.of(0));
            var cached = fixture.take(); assertEquals("blocktxn", cached.command());
            assertEquals(fixture.first.transactions().getFirst().txId(),
                    BitcoinMessages.decodeBlockTxn(cached, 2).transactions().getFirst().txId());
            var replacement = fixture.validation.findBlock(fixture.validation.activeTip().hash()).orElseThrow();
            fixture.relay.relayConnectedBlock(replacement, fixture.peer);
            fixture.request(fixture.first.hash(), List.of(0));
            assertEquals("block", fixture.take().command());
        }
    }

    @Test void invalidRecentIndexDisconnectsWithoutSendingPartialTransactions() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.request(fixture.first.hash(), List.of(0, Integer.MAX_VALUE));
            verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(
                    eq("getblocktxn transaction index out of bounds"), any());
            assertTrue(fixture.outgoing.isEmpty());
        }
    }

    private final class Fixture implements AutoCloseable {
        final RocksDbDatabase db = new RocksDbDatabase(directory.resolve("main"));
        final NodeValidationService validation = new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
        final PeerManager peers = new PeerManager();
        final Peer peer = mock(Peer.class);
        final AtomicReference<PeerMessageListener> incoming = new AtomicReference<>();
        final LinkedBlockingQueue<BitcoinMessage> outgoing = new LinkedBlockingQueue<>();
        final NodeRelayService relay;
        final Block first;

        Fixture() throws Exception {
            first = mine(validation); validation.processBlock(first);
            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                    VersionMessage.DEFAULT_SERVICES, 0, NetworkAddress.unspecified(), NetworkAddress.unspecified(),
                    1, "/blocktxn-test/", 1, true));
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer);
            relay = new NodeRelayService(validation, new NodeSyncInfrastructure(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L), peers);
        }
        void growTo(long height) throws Exception {
            while (validation.activeTip().height() < height) validation.processBlock(mine(validation));
        }
        void request(Hash256 hash, List<Integer> indexes) throws Exception {
            incoming.get().onMessage(peer, BitcoinMessages.getBlockTxn(new BlockTransactionsRequest(hash, indexes)));
        }
        BitcoinMessage take() throws Exception {
            var message = outgoing.poll(5, TimeUnit.SECONDS); assertNotNull(message); return message;
        }
        public void close() throws Exception {
            try { relay.close(); peers.close(); }
            finally { db.close(); }
        }
    }

    private static Block mine(NodeValidationService validation) throws Exception {
        var mining = new MiningController(validation, null, NetworkParametersRegistry.regtest(), () -> true,
                new byte[]{0x51}, 4_000_000, new FeeRate(0));
        return MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
    }
}
