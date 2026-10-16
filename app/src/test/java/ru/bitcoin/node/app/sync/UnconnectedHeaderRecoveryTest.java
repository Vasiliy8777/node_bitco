package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnconnectedHeaderRecoveryTest {
    @TempDir Path directory;
    private static final Hash256 STOP = new Hash256(new byte[32]);

    private static BlockHeader header(Hash256 parent, long time) {
        for (long nonce = 0; nonce < 1000; nonce++) {
            var candidate = new BlockHeader(4, parent, STOP, new UInt32(time), new UInt32(0x207fffffL), new UInt32(nonce));
            if (ProofOfWork.isValid(candidate, NetworkParametersRegistry.regtest())) return candidate;
        }
        throw new AssertionError("Could not mine regtest header");
    }

    @Test
    void unconnectedBatchIsRejectedAtomicallyAndNextPeerCanAdvanceHeaders() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var infrastructure = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var genesis = validation.activeTip();
            var valid = header(genesis.hash(), genesis.header().timestamp().value() + 1);
            var unknown = Hash256.fromDisplayHex("21".repeat(32));
            var orphan = header(unknown, genesis.header().timestamp().value() + 2);
            var downloader = mock(HeaderSynchronizer.class);
            when(downloader.download(anyList(), eq(STOP))).thenReturn(
                    new HeadersMessage(List.of(valid, orphan)), new HeadersMessage(List.of(valid)), new HeadersMessage(List.of()));
            var coordinator = new HeaderSyncCoordinator(downloader, infrastructure.headerSyncService(),
                    infrastructure.headerChainState(), infrastructure.blockLocatorBuilder());
            var failure = assertThrows(IOException.class, () -> coordinator.synchronize(STOP));
            var cause = assertInstanceOf(UnconnectedHeaderException.class, failure.getCause());
            assertEquals(unknown, cause.parentHash());
            assertEquals(orphan.hash(), cause.headerHash());
            assertTrue(new RocksDbBlockIndexStore(db).find(valid.hash()).isEmpty(), "Rejected batch partially persisted");
            assertEquals(genesis.hash(), infrastructure.headerChainState().bestHeaderTip().hash());
            assertEquals(genesis.hash(), new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
            assertEquals(1, coordinator.synchronize(STOP).size());
            assertEquals(valid.hash(), infrastructure.headerChainState().bestHeaderTip().hash());
        }
    }

    @Test
    void internalStateErrorsAreNotConvertedIntoPeerFailures() throws Exception {
        var tip = mock(BlockIndex.class);
        when(tip.hash()).thenReturn(STOP);
        var locator = mock(BlockLocatorBuilder.class);
        when(locator.build(tip)).thenReturn(List.of(STOP));
        var downloader = mock(HeaderSynchronizer.class);
        var response = new HeadersMessage(List.of(header(STOP, 1_700_000_000L)));
        when(downloader.download(anyList(), any())).thenReturn(response);
        var service = mock(HeaderSyncService.class);
        var internal = new IllegalStateException("Corrupt local index");
        when(service.process(eq(response), any())).thenThrow(internal);
        var coordinator = new HeaderSyncCoordinator(downloader, service, new HeaderChainState(tip), locator);
        assertSame(internal, assertThrows(IllegalStateException.class, () -> coordinator.synchronize(STOP)));
    }

    @Test
    void liveSyncReplacesOpenPeerWithUnconnectedHeaderAndKeepsRunning() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var infrastructure = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var orphan = header(Hash256.fromDisplayHex("21".repeat(32)), 1_700_000_000L);
            var bad = peerReply(new HeadersMessage(List.of(orphan)));
            var healthy = peerReply(new HeadersMessage(List.of()));
            var peers = mock(PeerManager.class);
            var ready = new AtomicReference<>(List.of(bad, healthy));
            when(peers.readyPeers()).thenAnswer(call -> ready.get());
            doAnswer(call -> { ready.set(List.of(healthy)); return null; }).when(bad).close();
            var blocks = mock(BlockSyncCoordinator.class);
            var live = new LiveChainSynchronizer(peers, infrastructure, blocks, validation, Duration.ofSeconds(2));
            var failure = new AtomicReference<Throwable>();
            var worker = Thread.ofPlatform().daemon().start(() -> {
                try { live.run(); } catch (Throwable exception) { failure.set(exception); }
            });
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (!live.isCurrent() && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
                assertNull(failure.get());
                assertTrue(live.isCurrent(), "Bad peer stopped live synchronization");
                verify(bad).close();
                verify(healthy, never()).close();
                verify(blocks).synchronizeToTip();
                assertTrue(worker.isAlive());
            } finally {
                live.close();
                worker.interrupt();
                worker.join(3000);
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
        }
    }

    private static Peer peerReply(HeadersMessage response) throws IOException {
        var peer = mock(Peer.class);
        when(peer.isReady()).thenReturn(true);
        when(peer.state()).thenReturn(PeerState.READY);
        var version = mock(VersionMessage.class);
        when(version.version()).thenReturn(VersionMessage.CURRENT_PROTOCOL_VERSION);
        when(peer.remoteVersion()).thenReturn(version);
        var dispatcher = new PeerMessageDispatcher(peer);
        when(peer.messageDispatcher()).thenReturn(dispatcher);
        doAnswer(call -> { dispatcher.dispatch(BitcoinMessages.headers(response)); return null; }).when(peer).send(any());
        return peer;
    }
}
