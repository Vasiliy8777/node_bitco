package ru.bitcoin.node.app.sync;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.bitcoin.node.app.*;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveHeaderRejectionTest {
    @ParameterizedTest
    @CsvSource({"TIME_FUTURE,false,false", "TIME_FUTURE,true,false",
            "CACHED_INVALID,true,false", "CACHED_INVALID,false,true", "INVALID,false,true", "INVALID,true,true"})
    void headerRejectionUsesCorePolicyAndFailsOver(BlockHeaderValidationException.Reason reason,
            boolean inbound, boolean punish) throws Exception {
        var header = GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header();
        var tip = mock(BlockIndex.class); when(tip.hash()).thenReturn(header.hash());
        var validation = mock(NodeValidationService.class); when(validation.activeTip()).thenReturn(tip);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        when(infrastructure.headerChainState()).thenReturn(new HeaderChainState(tip));
        var service = mock(HeaderSyncService.class); when(infrastructure.headerSyncService()).thenReturn(service);
        var locators = mock(BlockLocatorBuilder.class); when(locators.build(any())).thenReturn(List.of(header.hash()));
        when(infrastructure.blockLocatorBuilder()).thenReturn(locators);
        var rejected = mock(Peer.class); var healthy = mock(Peer.class);
        var version = mock(VersionMessage.class); when(version.version()).thenReturn(70016);
        for (var peer : List.of(rejected, healthy)) {
            when(peer.isReady()).thenReturn(true); when(peer.state()).thenReturn(PeerState.READY);
            when(peer.remoteVersion()).thenReturn(version);
            when(peer.messageDispatcher()).thenReturn(new PeerMessageDispatcher(peer));
        }
        when(rejected.isInboundConnection()).thenReturn(inbound);
        var invalid = new BlockHeaderValidationException("Rejected fixture header", reason);
        when(service.processPeerWithReception(eq(rejected), any(), any(), any())).thenThrow(invalid);
        doAnswer(call -> { rejected.messageDispatcher().dispatch(BitcoinMessages.headers(new HeadersMessage(List.of(header)))); return true; })
                .when(rejected).trySendGetHeaders(any());
        var healthyRequest = new CountDownLatch(1);
        doAnswer(call -> { healthy.messageDispatcher().dispatch(BitcoinMessages.headers(new HeadersMessage(List.of()))); healthyRequest.countDown(); return true; })
                .when(healthy).trySendGetHeaders(any());
        var peers = mock(PeerManager.class); when(peers.readyPeers()).thenReturn(List.of(rejected, healthy));
        var blocks = mock(BlockSyncCoordinator.class);
        var live = new LiveChainSynchronizer(peers, infrastructure, blocks, validation, Duration.ofSeconds(1));
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().daemon().start(() -> { try { live.run(); } catch (Throwable error) { failure.set(error); } });
        try {
            assertTrue(healthyRequest.await(5, TimeUnit.SECONDS), () -> "No failover: " + failure.get());
            verify(blocks, timeout(2000)).synchronizeToTip();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!live.isCurrent() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(live.isCurrent());
            verify(rejected, never()).close();
            verify(rejected, punish ? times(1) : never()).disconnectForProtocolViolation(anyString(), same(invalid));
            verify(healthy, never()).disconnectForProtocolViolation(anyString(), any());
        } finally { live.close(); worker.interrupt(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertNull(failure.get());
    }
}
