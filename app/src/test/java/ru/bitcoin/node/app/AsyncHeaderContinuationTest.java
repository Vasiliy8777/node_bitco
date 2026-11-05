package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncHeaderContinuationTest {
    @TempDir Path directory;

    @Test void fullAsyncReplyContinuesFromItsLastHeaderButShortAndEmptyRepliesDoNotLoop() throws Exception {
        try (var fixture = new Fixture(false)) {
            var batch = headers(2000);
            fixture.receive(batch);
            var next = fixture.take(); assertEquals("getheaders", next.command());
            var request = GetHeadersMessageCodec.decode(next.payload());
            assertEquals(batch.getLast().hash(), request.locatorHashes().getFirst());
            assertEquals(new Hash256(new byte[32]), request.stopHash());
            assertEquals(70016, request.protocolVersion());
            assertNotNull(fixture.sync.blockIndexLookup().find(batch.getLast().hash()));
            var ordered = inOrder(fixture.peer);
            ordered.verify(fixture.peer).headersResponseValidated();
            ordered.verify(fixture.peer).send(argThat(message -> "getheaders".equals(message.command())));

            // Known headers may still form a full response; Core also requests
            // the next locator in this case, independent of new-header credit.
            fixture.receive(batch); assertEquals("getheaders", fixture.take().command());
            fixture.receive(List.of(header(batch.getLast().hash(), batch.getLast().timestamp().value() + 1)));
            fixture.receive(List.of()); fixture.barrier();
            assertEquals("headers", fixture.take().command());
            assertTrue(fixture.outgoing.isEmpty());
            verify(fixture.peer, times(2)).send(argThat(message -> "getheaders".equals(message.command())));
            assertEquals(0, fixture.validation.activeTip().height(), "Header pagination must not activate bodies");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1, 2})
    void compactPayloadIsDecodedOnlyAfterSupportedVersionNegotiation(long version) throws Exception {
        try (var fixture = new Fixture(false)) {
            if (version != 0) fixture.incoming.get().onMessage(fixture.peer,
                    BitcoinMessages.sendCmpct(new SendCmpctMessage(false, version)));
            fixture.incoming.get().onMessage(fixture.peer, new BitcoinMessage("cmpctblock", new byte[]{(byte) 0xff}));
            if (version == 2) {
                verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(anyString(), any());
            } else {
                fixture.barrier(); assertEquals("headers", fixture.take().command());
                verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            }
            verify(fixture.peer, never()).headersResponseValidated();
        }
    }

    @Test void invalidFullBatchCannotReleaseAdmissionOrRequestMoreHeaders() throws Exception {
        try (var fixture = new Fixture(false)) {
            var batch = new ArrayList<>(headers(2000));
            var last = batch.getLast();
            batch.set(batch.size() - 1, new BlockHeader(last.version(), last.previousBlockHash(), last.merkleRoot(),
                    last.timestamp(), new UInt32(0), last.nonce()));
            fixture.receive(batch);
            verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.peer, never()).headersResponseValidated();
            verify(fixture.peer, never()).send(argThat(message -> "getheaders".equals(message.command())));
            assertNull(fixture.sync.blockIndexLookup().find(batch.getFirst().hash()), "Invalid batch persisted a prefix");
        }
    }

    @Test void failedBranchReportedByDownloadViewCannotDriveContinuation() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.receive(headers(2000)); fixture.barrier();
            assertEquals("headers", fixture.take().command());
            verify(fixture.peer, never()).send(argThat(message -> "getheaders".equals(message.command())));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void explicitlyInvalidKnownHeaderCannotAcknowledgeAsyncResponse(boolean inbound, boolean compact) throws Exception {
        try (var fixture = new Fixture(false)) {
            when(fixture.peer.isInboundConnection()).thenReturn(inbound);
            var batch = headers(2);
            fixture.sync.headerSyncService().process(new HeadersMessage(batch));
            new ru.bitcoin.node.storage.block.RocksDbBlockFailureStore(fixture.db)
                    .markFailed(batch.getFirst().hash());
            if (compact) fixture.receiveCompact(batch.getFirst());
            else fixture.receive(List.of(batch.getFirst()));
            if (inbound || compact) {
                fixture.barrier();
                assertEquals("headers", fixture.take().command());
                verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            } else {
                verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(anyString(), any());
            }
            verify(fixture.peer, times(compact ? 0 : 1)).headersResponseValidated();
            verify(fixture.peer, never()).recordNewBlockAnnouncement();
            verify(fixture.peer, never()).send(argThat(message -> "getheaders".equals(message.command())));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void futureHeaderDoesNotPunishPeerAndCanBeRetriedAfterTimeAdvances(boolean inbound, boolean compact) throws Exception {
        try (var fixture = new Fixture(false)) {
            when(fixture.peer.isInboundConnection()).thenReturn(inbound);
            var future = header(NetworkParametersRegistry.regtest().genesisBlockHash(), 1_800_007_201L);
            if (compact) {
                fixture.receiveCompact(future);
            } else {
                fixture.receive(List.of(future));
            }
            fixture.barrier();
            assertEquals("headers", fixture.take().command());
            assertNull(fixture.sync.blockIndexLookup().find(future.hash()));
            assertFalse(new ru.bitcoin.node.storage.block.RocksDbBlockFailureStore(fixture.db).isFailed(future.hash()));
            verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.peer, never()).close();
            verify(fixture.peer, times(compact ? 0 : 1)).headersResponseValidated();
            verify(fixture.peer, never()).recordNewBlockAnnouncement();
            assertTrue(fixture.outgoing.isEmpty(), "Rejected compact header must not request transactions or fallback");
            fixture.clock.incrementAndGet();
            fixture.receive(List.of(future));
            fixture.barrier();
            assertEquals("headers", fixture.take().command());
            assertNotNull(fixture.sync.blockIndexLookup().find(future.hash()));
            verify(fixture.peer, times(compact ? 1 : 2)).headersResponseValidated();
            verify(fixture.peer).recordNewBlockAnnouncement();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void invalidProofOfWorkCannotUseFutureTimeExemption(boolean compact) throws Exception {
        try (var fixture = new Fixture(false)) {
            var valid = header(NetworkParametersRegistry.regtest().genesisBlockHash(), 1_800_007_201L);
            var invalid = new BlockHeader(valid.version(), valid.previousBlockHash(), valid.merkleRoot(),
                    valid.timestamp(), new UInt32(0), valid.nonce());
            if (compact) fixture.receiveCompact(invalid);
            else fixture.receive(List.of(invalid));
            verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.peer, never()).headersResponseValidated();
            assertNull(fixture.sync.blockIndexLookup().find(invalid.hash()));
        }
    }

    @Test void unconnectedFullBatchRequestsBestHeaderLocatorAndRetainsAvailability() throws Exception {
        try (var fixture = new Fixture(false)) {
            var chain = headers(2001);
            var orphanBatch = chain.subList(1, chain.size());
            var genesis = NetworkParametersRegistry.regtest().genesisBlockHash();
            var alternative = header(genesis, chain.getLast().timestamp().value() + 1);
            fixture.sync.headerSyncService().process(new HeadersMessage(List.of(alternative)));
            fixture.receive(orphanBatch);
            var request = GetHeadersMessageCodec.decode(fixture.take().payload());
            assertEquals(alternative.hash(), request.locatorHashes().getFirst(), "Recovery must start at best header, not active tip");
            assertEquals(new Hash256(new byte[32]), request.stopHash());
            assertEquals(chain.getLast().hash(), fixture.announced.get());
            fixture.barrier(); assertEquals("headers", fixture.take().command());
            assertTrue(fixture.outgoing.isEmpty(), "Orphan batch must not start a pagination loop");
            assertNull(fixture.sync.blockIndexLookup().find(orphanBatch.getFirst().hash()));
            verify(fixture.peer, never()).headersResponseValidated();
            verify(fixture.peer, never()).recordNewBlockAnnouncement();
            verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            assertFalse(fixture.relay.outboundRecoveryView().hasTipWork(fixture.peer));
            // A different peer can fill the missing chain; the retained hash then
            // proves this original peer's availability without a second announcement.
            fixture.sync.headerSyncService().process(new HeadersMessage(chain.subList(0, 2000)));
            fixture.sync.headerSyncService().process(new HeadersMessage(chain.subList(2000, chain.size())));
            assertTrue(fixture.relay.outboundRecoveryView().hasTipWork(fixture.peer));
            verify(fixture.peer, never()).recordNewBlockAnnouncement();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void invalidWorkAnywhereInUnconnectedBatchCannotTriggerRecovery(boolean invalidLast) throws Exception {
        try (var fixture = new Fixture(false)) {
            var chain = headers(3);
            var orphan = new ArrayList<>(chain.subList(1, 3));
            int position = invalidLast ? 1 : 0;
            var valid = orphan.get(position);
            orphan.set(position, new BlockHeader(valid.version(), valid.previousBlockHash(), valid.merkleRoot(),
                    valid.timestamp(), new UInt32(0), valid.nonce()));
            fixture.receive(orphan);
            verify(fixture.peer, timeout(3000)).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.peer, never()).send(argThat(message -> "getheaders".equals(message.command())));
            verify(fixture.peer, never()).headersResponseValidated();
            verify(fixture.peer, never()).noteBlockAnnouncement(any());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void compactUnknownParentRequestsHeadersOnlyOutsideIbd(boolean ibd, boolean invalidWork) throws Exception {
        try (var fixture = new Fixture(false, ibd)) {
            var chain = headers(2);
            var orphan = chain.getLast();
            if (invalidWork) orphan = new BlockHeader(orphan.version(), orphan.previousBlockHash(), orphan.merkleRoot(),
                    orphan.timestamp(), new UInt32(0), orphan.nonce());
            fixture.receiveCompact(orphan);
            fixture.barrier();
            if (!ibd) {
                var recovery = fixture.take(); assertEquals("getheaders", recovery.command());
                assertEquals(NetworkParametersRegistry.regtest().genesisBlockHash(),
                        GetHeadersMessageCodec.decode(recovery.payload()).locatorHashes().getFirst());
            }
            assertEquals("headers", fixture.take().command());
            assertTrue(fixture.outgoing.isEmpty());
            assertNull(fixture.sync.blockIndexLookup().find(orphan.hash()));
            verify(fixture.peer, never()).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.peer, never()).headersResponseValidated();
            verify(fixture.peer, never()).noteBlockAnnouncement(any());
            verify(fixture.peer, never()).recordNewBlockAnnouncement();
        }
    }

    private final class Fixture implements AutoCloseable {
        final RocksDbDatabase db = new RocksDbDatabase(directory);
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_800_000_000L);
        final NodeValidationService validation = spy(new NodeValidationService(db, NetworkParametersRegistry.regtest(), clock::get, new Mempool()));
        final NodeSyncInfrastructure sync = spy(new NodeSyncInfrastructure(db, NetworkParametersRegistry.regtest(), clock::get));
        final PeerManager peers = new PeerManager();
        final Peer peer = mock(Peer.class);
        final AtomicReference<PeerMessageListener> incoming = new AtomicReference<>();
        final LinkedBlockingQueue<BitcoinMessage> outgoing = new LinkedBlockingQueue<>();
        final AtomicReference<Hash256> announced = new AtomicReference<>();
        final NodeRelayService relay;
        Fixture(boolean failed) throws Exception {
            this(failed, null);
        }
        Fixture(boolean failed, Boolean ibd) throws Exception {
            if (ibd != null) doReturn(ibd).when(validation).downloadInitialBlockDownload();
            if (failed) doReturn(true).when(sync).downloadBlockFailed(any());
            when(peer.lastBlockAnnouncement()).thenAnswer(call -> announced.get());
            doAnswer(call -> { announced.set(call.getArgument(0)); return null; }).when(peer).noteBlockAnnouncement(any());
            when(peer.isReady()).thenReturn(true);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(70016, VersionMessage.DEFAULT_SERVICES,
                    0, NetworkAddress.unspecified(), NetworkAddress.unspecified(), 1, "/async-headers-test/", 2000, true));
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer); relay = new NodeRelayService(validation, sync, peers);
        }
        void receive(List<BlockHeader> headers) throws Exception { incoming.get().onMessage(peer, BitcoinMessages.headers(new HeadersMessage(headers))); }
        void receiveCompact(BlockHeader header) throws Exception {
            incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(false, 2)));
            incoming.get().onMessage(peer, BitcoinMessages.compactBlock(
                    new CompactBlockMessage(header, 42, List.of(1L), List.of()), 2));
        }
        void barrier() throws Exception {
            incoming.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(70016,
                    List.of(NetworkParametersRegistry.regtest().genesisBlockHash()), new Hash256(new byte[32]))));
        }
        BitcoinMessage take() throws Exception { var message = outgoing.poll(5, TimeUnit.SECONDS); assertNotNull(message); return message; }
        public void close() throws Exception { try { relay.close(); peers.close(); } finally { db.close(); } }
    }

    private static List<BlockHeader> headers(int count) {
        var parameters = NetworkParametersRegistry.regtest();
        var previous = ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(parameters).header();
        var headers = new ArrayList<BlockHeader>();
        for (int i = 0; i < count; i++) { previous = header(previous.hash(), previous.timestamp().value() + 1); headers.add(previous); }
        return List.copyOf(headers);
    }
    private static BlockHeader header(Hash256 parent, long timestamp) {
        var zero = new Hash256(new byte[32]);
        for (long nonce = 0; nonce < 1000; nonce++) {
            var header = new BlockHeader(4, parent, zero, new UInt32(timestamp), new UInt32(0x207fffffL), new UInt32(nonce));
            if (ProofOfWork.isValid(header, NetworkParametersRegistry.regtest())) return header;
        }
        throw new AssertionError("Unable to mine regtest header");
    }
}
