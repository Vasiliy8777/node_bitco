package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.*;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.*;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeadersPresyncIntegrationTest {
    @TempDir Path directory;
    private static final NetworkParameters REGTEST = NetworkParametersRegistry.regtest();
    private static final Hash256 ZERO = new Hash256(new byte[32]);
    private static final List<BlockHeader> CHAIN = chain(6000);

    @Test void asyncWireRepliesAreNotPersistedUntilVerifiedRedownload() throws Exception {
        try (var fixture = new Fixture(true)) {
            for (int batch = 0; batch < 3; batch++) {
                fixture.respond(batch);
                assertEquals(batch == 2 ? REGTEST.genesisBlockHash() : CHAIN.get((batch + 1) * 2000 - 1).hash(), fixture.takeLocator());
                assertEquals(0, fixture.sync.headerChainState().bestHeaderTip().height());
                assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
                assertFalse(fixture.clients.hasChainSyncProtection(fixture.peer));
                assertEquals((batch + 1) * 2000L, fixture.sync.headerSyncService().presyncedHeight(fixture.peer));
            }
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            assertEquals(6000, fixture.sync.headerSyncService().presyncedHeight(fixture.peer), "Redownload retains the presync height");
            fixture.respond(1); assertEquals(CHAIN.get(3999).hash(), fixture.takeLocator());
            assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
            fixture.respond(2); assertEquals(CHAIN.getLast().hash(), fixture.takeLocator());
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of())));
            fixture.barrier();
            assertEquals("headers", fixture.take().command());
            assertTrue(fixture.wire.isEmpty(), "Empty response after the final continuation must stop pagination");
            assertEquals(6000, fixture.sync.headerChainState().bestHeaderTip().height());
            assertEquals(6000, fixture.sync.blockIndexLookup().find(CHAIN.getLast().hash()).height());
            assertEquals(-1, fixture.sync.headerSyncService().presyncedHeight(fixture.peer));
            assertEquals(0, fixture.validation.activeTip().height(), "Headers must not activate block bodies");
            assertTrue(fixture.clients.hasChainSyncProtection(fixture.peer));
        }
    }

    @Test void synchronousWaiterResumesTheSamePresyncStartedByAsyncRelay() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            fixture.respond(1); assertEquals(CHAIN.get(3999).hash(), fixture.takeLocator());
            var batches = new AtomicInteger(); var progress = new AtomicInteger();
            var task = fixture.synchronize(batches, progress);
            await(() -> fixture.peer.messageDispatcher().hasPendingHeaders());
            fixture.respond(2); assertEquals(REGTEST.genesisBlockHash(), fixture.takeLocator());
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            fixture.respond(1); assertEquals(CHAIN.get(3999).hash(), fixture.takeLocator());
            assertEquals(0, progress.get());
            fixture.respond(2); assertEquals(CHAIN.getLast().hash(), fixture.takeLocator());
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of())));
            assertEquals(6000L, task.get(20, TimeUnit.SECONDS));
            assertEquals(1, batches.get()); assertEquals(6000, progress.get());
            assertEquals(0, fixture.validation.activeTip().height());
        }
    }

    @Test void initialSyncUsesPresyncInsteadOfPersistingLowWorkHeaders() throws Exception {
        try (var fixture = new Fixture(false)) {
            var batches = new AtomicInteger(); var progress = new AtomicInteger();
            var task = fixture.synchronize(batches, progress);
            assertEquals(REGTEST.genesisBlockHash(), fixture.takeLocator());
            for (int batch = 0; batch < 3; batch++) {
                fixture.respond(batch);
                assertEquals(batch == 2 ? REGTEST.genesisBlockHash() : CHAIN.get((batch + 1) * 2000 - 1).hash(), fixture.takeLocator());
                assertEquals(0, progress.get());
            }
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            fixture.respond(1); assertEquals(CHAIN.get(3999).hash(), fixture.takeLocator());
            fixture.respond(2); assertEquals(CHAIN.getLast().hash(), fixture.takeLocator());
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of())));
            assertEquals(6000L, task.get(20, TimeUnit.SECONDS));
            assertEquals(1, batches.get()); assertEquals(6000, progress.get());
        }
    }

    @Test void shortLowWorkReplyAndCompactBlockCannotBypassTheThreshold() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of(CHAIN.getFirst()))));
            fixture.remote.get().send(BitcoinMessages.sendCmpct(new SendCmpctMessage(false, 2)));
            fixture.remote.get().send(BitcoinMessages.compactBlock(
                    new CompactBlockMessage(CHAIN.getFirst(), 42, List.of(1L), List.of()), 2));
            fixture.barrier(); assertEquals("headers", fixture.take().command());
            assertTrue(fixture.wire.isEmpty());
            assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
            assertTrue(fixture.peer.isReady());
        }
    }

    @Test void disconnectedRedownloadAndEmptyReplyResetStateWithoutReleasingUnconnectedAdmission() throws Exception {
        try (var fixture = new Fixture(true)) {
            for (int batch = 0; batch < 3; batch++) { fixture.respond(batch); fixture.takeLocator(); }
            var disconnected = mine(ZERO, CHAIN.getLast().timestamp().value() + 1);
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of(disconnected))));
            fixture.barrier(); assertEquals("headers", fixture.take().command());
            assertTrue(fixture.wire.isEmpty(), "Unconnected reply must not release the recent GETHEADERS gate");
            assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
            assertTrue(fixture.peer.isReady());
            fixture.remote.get().send(BitcoinMessages.headers(new HeadersMessage(List.of())));
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
        }
    }

    @Test void simultaneousPeersKeepIndependentPresyncCursors() throws Exception {
        try (var fixture = new Fixture(true)) {
            var firstRemote = fixture.remote.get();
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            fixture.respond(1); assertEquals(CHAIN.get(3999).hash(), fixture.takeLocator());
            var second = new BitcoinClient(fixture.parameters).connectManaged("127.0.0.1", fixture.server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
            fixture.clients.add(second, PeerConnectionRole.FULL_RELAY);
            await(() -> fixture.remote.get() != firstRemote);
            fixture.respond(0); assertEquals(CHAIN.get(1999).hash(), fixture.takeLocator());
            firstRemote.send(BitcoinMessages.headers(new HeadersMessage(CHAIN.subList(4000, 6000))));
            assertEquals(REGTEST.genesisBlockHash(), fixture.takeLocator(), "Peer two overwrote peer one's PRESYNC state");
            assertNull(fixture.sync.blockIndexLookup().find(CHAIN.getFirst().hash()));
            assertTrue(fixture.peer.isReady()); assertTrue(second.isReady());
        }
    }

    @Test void workThresholdIncludes144BlockBufferAndKnownAncestorsAreExempt() {
        var genesis = GenesisBlockFactory.create(REGTEST).header();
        var start = new BlockIndex(genesis.hash(), genesis, 0, ZERO, ChainWork.blockWork(genesis.bits().value()));
        var indexes = new HashMap<Hash256, BlockIndex>(); indexes.put(start.hash(), start); var tip = start;
        for (var header : CHAIN.subList(0, 200)) { tip = BlockIndexFactory.createChild(tip, header); indexes.put(tip.hash(), tip); }
        BlockIndex active = tip; BlockIndexLookup lookup = indexes::get;
        var controller = new HeadersPresyncController(REGTEST, lookup, new HeaderChainState(tip),
                new BlockLocatorBuilder(lookup), () -> active, () -> 1_800_000_000L);
        assertEquals(BigInteger.valueOf(114), controller.threshold());
        var acknowledgements = new AtomicInteger();
        var known = controller.receive(new Object(), List.of(CHAIN.getFirst()), acknowledgements::incrementAndGet);
        assertFalse(known.handled()); assertEquals(1, known.ready().size());
        var alternative = mine(genesis.hash(), genesis.timestamp().value() + 2);
        var ignored = controller.receive(new Object(), List.of(alternative), acknowledgements::incrementAndGet);
        assertTrue(ignored.handled()); assertTrue(ignored.ready().isEmpty());
        assertEquals(2, acknowledgements.get());
    }

    private final class Fixture implements AutoCloseable {
        final NetworkParameters parameters = spy(REGTEST);
        final RocksDbDatabase db = new RocksDbDatabase(directory);
        final PeerManager serving = new PeerManager(), clients = new PeerManager();
        final AtomicReference<Peer> remote = new AtomicReference<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final BlockingQueue<BitcoinMessage> wire = new LinkedBlockingQueue<>();
        final NodeValidationService validation;
        final NodeSyncInfrastructure sync;
        final BitcoinServer server;
        final Peer peer;
        final NodeRelayService relay;
        Fixture(boolean async) throws Exception {
            doReturn(BigInteger.valueOf(9000)).when(parameters).minimumChainWork();
            validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            serving.addPeerListener(connection -> {
                remote.set(connection);
                connection.addMessageListener((source, message) -> {
                    if (Set.of("getheaders", "headers", "getdata", "getblocktxn").contains(message.command())) wire.add(message);
                });
                connected.countDown();
            });
            server = new BitcoinServer(parameters, serving); server.start(0, 0);
            peer = new BitcoinClient(parameters).connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
            clients.add(peer, PeerConnectionRole.FULL_RELAY); assertTrue(connected.await(3, TimeUnit.SECONDS));
            relay = async ? new NodeRelayService(validation, sync, clients) : null;
        }
        CompletableFuture<Long> synchronize(AtomicInteger batches, AtomicInteger progress) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new HeaderSyncCoordinator(new HeaderSynchronizer(peer, Duration.ofSeconds(10)),
                            sync.headerSyncService(), sync.headerChainState(), sync.blockLocatorBuilder())
                            .synchronize(ZERO, indexes -> batches.incrementAndGet(), index -> progress.incrementAndGet());
                } catch (Exception failure) { throw new CompletionException(failure); }
            });
        }
        void respond(int batch) throws Exception { remote.get().send(BitcoinMessages.headers(new HeadersMessage(CHAIN.subList(batch * 2000, (batch + 1) * 2000)))); }
        void barrier() throws Exception { remote.get().send(BitcoinMessages.getHeaders(new GetHeadersMessage(70016, List.of(REGTEST.genesisBlockHash()), ZERO))); }
        BitcoinMessage take() throws Exception { var message = wire.poll(15, TimeUnit.SECONDS); assertNotNull(message); return message; }
        Hash256 takeLocator() throws Exception { var message = take(); assertEquals("getheaders", message.command()); return GetHeadersMessageCodec.decode(message.payload()).locatorHashes().getFirst(); }
        public void close() throws Exception {
            try { if (relay != null) relay.close(); clients.close(); server.close(); serving.close(); }
            finally { validation.close(); db.close(); }
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static List<BlockHeader> chain(int count) {
        var result = new ArrayList<BlockHeader>(); var previous = GenesisBlockFactory.create(REGTEST).header();
        for (int i = 0; i < count; i++) { previous = mine(previous.hash(), previous.timestamp().value() + 1); result.add(previous); }
        return List.copyOf(result);
    }
    private static BlockHeader mine(Hash256 parent, long timestamp) {
        for (long nonce = 0; nonce < 10000; nonce++) {
            var header = new BlockHeader(4, parent, ZERO, new UInt32(timestamp), new UInt32(0x207fffffL), new UInt32(nonce));
            if (ProofOfWork.isValid(header, REGTEST)) return header;
        }
        throw new AssertionError("Regtest mining failed");
    }
}
