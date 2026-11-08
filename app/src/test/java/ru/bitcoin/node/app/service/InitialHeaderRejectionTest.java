package ru.bitcoin.node.app.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.bitcoin.node.app.*;
import ru.bitcoin.node.app.sync.*;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.address.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialHeaderRejectionTest {
    @ParameterizedTest @ValueSource(strings = {"INVALID", "TIME_FUTURE", "CACHED_INVALID", "MALFORMED", "INTERNAL",
            "TIMEOUT_NOBAN", "TIMEOUT_ORDINARY", "TRANSPORT_NOBAN"})
    void initialHeaderFailureIsScopedToPeerUnlessItIsInternal(String kind) throws Exception {
        var header = GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header();
        var tip = BlockIndexFactory.createGenesis(header);
        var validation = mock(NodeValidationService.class); when(validation.activeTip()).thenReturn(tip);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        when(infrastructure.headerSynchronizer(any(), any(), any(), any())).thenAnswer(call ->
                new ru.bitcoin.node.p2p.sync.HeaderSynchronizer(call.getArgument(0), call.getArgument(2)));
        when(infrastructure.headerChainState()).thenReturn(new HeaderChainState(tip));
        var headers = mock(HeaderSyncService.class); when(infrastructure.headerSyncService()).thenReturn(headers);
        var locators = mock(BlockLocatorBuilder.class); when(locators.build(any())).thenReturn(List.of(header.hash()));
        when(infrastructure.blockLocatorBuilder()).thenReturn(locators);
        var rejected = mock(Peer.class); var healthy = mock(Peer.class);
        var version = mock(VersionMessage.class); when(version.version()).thenReturn(70016);
        for (var peer : List.of(rejected, healthy)) {
            when(peer.isReady()).thenReturn(true); when(peer.state()).thenReturn(PeerState.READY);
            when(peer.remoteVersion()).thenReturn(version);
            when(peer.messageDispatcher()).thenReturn(new PeerMessageDispatcher(peer));
        }
        RuntimeException rejection = switch (kind) {
            case "MALFORMED" -> new IllegalArgumentException("Non-continuous headers response");
            case "INTERNAL" -> new IllegalStateException("Synthetic storage/programming failure");
            case "TIMEOUT_NOBAN", "TIMEOUT_ORDINARY", "TRANSPORT_NOBAN" -> new IllegalStateException("Unused network fixture marker");
            default -> new BlockHeaderValidationException("Rejected initial header", BlockHeaderValidationException.Reason.valueOf(kind));
        };
        when(headers.processPeerWithReception(eq(rejected), any(), any(), any())).thenThrow(rejection);
        when(rejected.hasPermission(PeerPermission.NOBAN)).thenReturn(kind.endsWith("_NOBAN"));
        doAnswer(call -> {
            if (kind.equals("TRANSPORT_NOBAN")) throw new java.io.IOException("Synthetic transport failure");
            if (!kind.startsWith("TIMEOUT_")) rejected.messageDispatcher().dispatch(BitcoinMessages.headers(new HeadersMessage(List.of(header))));
            return true;
        })
                .when(rejected).trySendGetHeaders(any());
        doAnswer(call -> { healthy.messageDispatcher().dispatch(BitcoinMessages.headers(new HeadersMessage(List.of()))); return true; })
                .when(healthy).trySendGetHeaders(any());
        var badAddress = new PeerAddress(InetAddress.getLoopbackAddress(), 18444, 1);
        var goodAddress = new PeerAddress(InetAddress.getLoopbackAddress(), 18445, 1);
        var first = new OutboundPeerConnection(rejected, badAddress);
        var second = new OutboundPeerConnection(healthy, goodAddress);
        var outbound = mock(OutboundPeerManager.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(outbound.connectHeaderPeerRace(anyInt(), anyList(), anyInt())).thenAnswer(call -> {
            if (attempts.getAndIncrement() == 0) return first;
            assertTrue(((List<?>) call.getArgument(1)).contains(badAddress));
            return second;
        });
        var supervisor = mock(OutboundPeerSupervisor.class); var peers = mock(PeerManager.class);
        var blocks = mock(BlockSyncCoordinator.class);
        var lifecycle = new NodeLifecycleService(validation, infrastructure, new PeerAddressManager(),
                mock(PeerDiscovery.class), outbound, supervisor, peers, mock(BitcoinServer.class), blocks,
                Duration.ofMillis(100), false, 18444);
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().daemon().start(() -> { try { lifecycle.start(); } catch (Throwable error) { failure.set(error); } });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lifecycle.state() != NodeLifecycleState.RUNNING && lifecycle.state() != NodeLifecycleState.FAILED
                    && System.nanoTime() < deadline) Thread.sleep(5);
            if (kind.equals("INTERNAL")) {
                worker.join(2000);
                assertEquals(NodeLifecycleState.FAILED, lifecycle.state()); assertSame(rejection, failure.get());
                assertEquals(1, attempts.get()); verify(supervisor, never()).start(any());
            } else {
                assertEquals(NodeLifecycleState.RUNNING, lifecycle.state(), () -> "Startup failed: " + failure.get());
                assertTrue(lifecycle.failure().isEmpty()); assertNull(failure.get()); assertEquals(2, attempts.get());
                verify(supervisor).start(second); verify(blocks).synchronizeToTip();
                boolean close = kind.equals("TIMEOUT_ORDINARY") || kind.equals("TRANSPORT_NOBAN");
                verify(rejected, close ? times(1) : never()).close();
                verify(peers, close ? times(1) : never()).remove(rejected);
                boolean punish = Set.of("INVALID", "CACHED_INVALID", "MALFORMED").contains(kind);
                verify(rejected, punish ? times(1) : never())
                        .disconnectForProtocolViolation(anyString(), same(rejection));
                if (kind.startsWith("TIMEOUT_")) verify(rejected, never()).headersResponseValidated();
            }
        } finally { lifecycle.close(); worker.interrupt(); worker.join(3000); }
        assertFalse(worker.isAlive());
    }
}
