package ru.bitcoin.node.app.sync;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveHeaderTimeoutTest {
    @ParameterizedTest @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,false,true"})
    void headerTimeoutFailsOverWithoutClosingNoBan(boolean noBan, boolean manual, boolean transportFailure) throws Exception {
        var tip = mock(BlockIndex.class); var hash = new Hash256(new byte[32]);
        when(tip.hash()).thenReturn(hash);
        var validation = mock(NodeValidationService.class); when(validation.activeTip()).thenReturn(tip);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        when(infrastructure.headerChainState()).thenReturn(new HeaderChainState(tip));
        when(infrastructure.headerSyncService()).thenReturn(mock(ru.bitcoin.node.app.HeaderSyncService.class));
        var locators = mock(BlockLocatorBuilder.class); when(locators.build(any())).thenReturn(List.of(hash));
        when(infrastructure.blockLocatorBuilder()).thenReturn(locators);
        var silent = mock(Peer.class); var healthy = mock(Peer.class);
        var version = mock(VersionMessage.class); when(version.version()).thenReturn(70016);
        for (var peer : List.of(silent, healthy)) {
            when(peer.isReady()).thenReturn(true); when(peer.state()).thenReturn(PeerState.READY);
            when(peer.remoteVersion()).thenReturn(version);
            when(peer.messageDispatcher()).thenReturn(new PeerMessageDispatcher(peer));
        }
        when(silent.hasPermission(PeerPermission.NOBAN)).thenReturn(noBan);
        when(silent.isManualConnection()).thenReturn(manual);
        when(silent.trySendGetHeaders(any())).thenReturn(true);
        if (transportFailure) when(silent.trySendGetHeaders(any())).thenThrow(new java.io.IOException("Transport failed"));
        var request = new CountDownLatch(1);
        doAnswer(call -> { healthy.messageDispatcher().dispatch(BitcoinMessages.headers(new HeadersMessage(List.of()))); request.countDown(); return true; })
                .when(healthy).trySendGetHeaders(any());
        var peers = mock(PeerManager.class); when(peers.readyPeers()).thenReturn(List.of(silent, healthy));
        var blocks = mock(BlockSyncCoordinator.class);
        var live = new LiveChainSynchronizer(peers, infrastructure, blocks, validation, Duration.ofMillis(100));
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().daemon().start(() -> { try { live.run(); } catch (Throwable error) { failure.set(error); } });
        try {
            assertTrue(request.await(5, TimeUnit.SECONDS), () -> "No failover: " + failure.get());
            verify(blocks, timeout(2000)).synchronizeToTip();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!live.isCurrent() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(live.isCurrent());
            if (noBan && !transportFailure) verify(silent, never()).close(); else verify(silent).close();
            verify(silent, never()).headersResponseValidated();
        } finally { live.close(); worker.interrupt(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertNull(failure.get());
    }
}
