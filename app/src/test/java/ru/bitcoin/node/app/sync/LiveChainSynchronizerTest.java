package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockLocatorBuilder;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveChainSynchronizerTest {
    @Test
    void emptyHeadersAndFailedNeighborPreserveVerifiedWorkButNewBlockInvalidatesIt() throws Exception {
        var tip = mock(BlockIndex.class);
        var tipHash = new Hash256(new byte[32]);
        when(tip.hash()).thenReturn(tipHash);
        var validation = mock(NodeValidationService.class);
        when(validation.activeTip()).thenReturn(tip);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        var headerState = new HeaderChainState(tip);
        when(infrastructure.headerChainState()).thenReturn(headerState);
        var headerService = mock(ru.bitcoin.node.app.HeaderSyncService.class);
        when(infrastructure.headerSyncService()).thenReturn(headerService);
        var locator = mock(BlockLocatorBuilder.class);
        when(locator.build(any())).thenReturn(List.of(tipHash));
        when(infrastructure.blockLocatorBuilder()).thenReturn(locator);
        var blocks = mock(BlockSyncCoordinator.class);
        var healthy = mock(Peer.class);
        when(healthy.isReady()).thenReturn(true);
        when(healthy.state()).thenReturn(PeerState.READY);
        var version = mock(VersionMessage.class);
        when(version.version()).thenReturn(VersionMessage.CURRENT_PROTOCOL_VERSION);
        when(healthy.remoteVersion()).thenReturn(version);
        var dispatcher = new PeerMessageDispatcher(healthy);
        when(healthy.messageDispatcher()).thenReturn(dispatcher);
        var messageListener = new AtomicReference<PeerMessageListener>();
        var inventoryListener = new AtomicReference<PeerInventoryListener>();
        doAnswer(call -> { messageListener.set(call.getArgument(0)); return null; })
                .when(healthy).addMessageListener(any());
        doAnswer(call -> { inventoryListener.set(call.getArgument(0)); return null; })
                .when(healthy).addInventoryListener(any());
        var empty = BitcoinMessages.headers(new HeadersMessage(List.of()));
        var requests = new AtomicInteger();
        var hold = new AtomicBoolean();
        var heldRequest = new CountDownLatch(1);
        doAnswer(call -> {
            requests.incrementAndGet();
            if (hold.get()) heldRequest.countDown();
            else {
                messageListener.get().onMessage(healthy, empty);
                dispatcher.dispatch(empty);
            }
            return null;
        }).when(healthy).send(any());
        var failed = mock(Peer.class);
        // Disconnect between the loop's readiness check and the actual request.
        when(failed.isReady()).thenReturn(true, false);
        when(failed.state()).thenReturn(PeerState.CLOSED);
        var failureHandled = new CountDownLatch(1);
        doAnswer(call -> { failureHandled.countDown(); return null; }).when(failed).close();
        var peers = mock(PeerManager.class);
        var ready = new AtomicReference<>(List.of(healthy, failed));
        when(peers.readyPeers()).thenAnswer(call -> ready.get());
        var live = new LiveChainSynchronizer(peers, infrastructure, blocks, validation, Duration.ofSeconds(2));
        var workerFailure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().daemon().start(() -> {
            try { live.run(); } catch (Throwable failure) { workerFailure.set(failure); }
        });
        try {
            assertTrue(failureHandled.await(3, TimeUnit.SECONDS), () -> "Worker failure: " + workerFailure.get());
            assertTrue(live.isCurrent(), "Failed neighbor discarded healthy peer's verification");
            messageListener.get().onMessage(healthy, empty);
            Thread.sleep(100);
            assertEquals(1, requests.get(), "Empty headers created a GETHEADERS feedback loop");
            assertTrue(live.isCurrent());
            ready.set(List.of());
            assertFalse(live.isCurrent(), "Mining remained ready with no peers");
            ready.set(List.of(healthy));
            var freshness = LiveChainSynchronizer.class.getDeclaredField("lastSuccess");
            freshness.setAccessible(true);
            freshness.setLong(live, System.nanoTime() - Duration.ofSeconds(61).toNanos());
            assertFalse(live.isCurrent(), "Expired verification must still stop mining");
            hold.set(true);
            inventoryListener.get().onInventory(healthy, new InvMessage(List.of(
                    new InventoryVector(InventoryVector.MSG_BLOCK, Hash256.fromDisplayHex("01".repeat(32))))));
            assertTrue(heldRequest.await(2, TimeUnit.SECONDS));
            assertFalse(live.isCurrent(), "New block announcement must invalidate work until checked");
        } finally {
            live.close();
            dispatcher.dispatch(empty);
            worker.interrupt();
            worker.join(3_000);
        }
        assertFalse(worker.isAlive());
        assertNull(workerFailure.get());
    }
}
