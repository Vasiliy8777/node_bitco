package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.PongMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PeerLivenessIsolationTest {
    @Test
    void blockedWriteDoesNotDelayOtherPeersOrCreateDuplicateTasks() throws Exception {
        Peer slow = mock(Peer.class);
        Peer healthy = mock(Peer.class);
        PeerManager manager = mock(PeerManager.class);
        when(manager.readyPeers()).thenReturn(List.of(slow, healthy));
        when(slow.isPingDue(anyLong(), anyLong())).thenReturn(true);
        when(healthy.isPingDue(anyLong(), anyLong())).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherSent = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return true;
        }).when(slow).sendPingIfDue(anyLong(), anyLong(), anyLong());
        doAnswer(call -> { otherSent.countDown(); return true; })
                .when(healthy).sendPingIfDue(anyLong(), anyLong(), anyLong());
        doAnswer(call -> { release.countDown(); return null; })
                .when(slow).handleReaderFailure(any(IOException.class));
        try (PeerLivenessService service = new PeerLivenessService(manager)) {
            service.checkPeers();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(otherSent.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) service.checkPeers();
            verify(slow, times(1)).sendPingIfDue(anyLong(), anyLong(), anyLong());
        } finally {
            release.countDown();
        }
        verify(slow).handleReaderFailure(argThat(e -> e.getMessage().contains("stopped")));
    }

    @Test
    void stalledWriteIsClosedByHeartbeatDeadline() throws Exception {
        Peer slow = mock(Peer.class);
        PeerManager manager = mock(PeerManager.class);
        when(manager.readyPeers()).thenReturn(List.of(slow));
        when(slow.isPingDue(anyLong(), anyLong())).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            return true;
        }).when(slow).sendPingIfDue(anyLong(), anyLong(), anyLong());
        doAnswer(call -> {
            when(slow.isPingDue(anyLong(), anyLong())).thenReturn(false);
            closed.countDown();
            return null;
        }).when(slow).handleReaderFailure(any(IOException.class));
        try (PeerLivenessService service = new PeerLivenessService(manager,
                Duration.ofMinutes(2), Duration.ofMillis(100), Duration.ofMillis(5), new Random(1))) {
            service.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            verify(slow).handleReaderFailure(argThat(e -> e.getMessage().contains("write timeout")));
        } finally {
            closed.countDown();
        }
    }

    @Test
    void pingStateRemainsAccessibleAndFastPongIsNotLostDuringWrite() throws Exception {
        PeerConnection connection = mock(PeerConnection.class);
        Peer peer = spy(new Peer(connection, 0, 0, true));
        doReturn(true).when(peer).isReady();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(connection).send(any());
        long now = System.nanoTime();
        CompletableFuture<Boolean> send = CompletableFuture.supplyAsync(() -> {
            try { return peer.sendPingIfDue(now, 1, 42); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertTrue(peer.pingTimedOut(now + 100, 50));
                peer.handleMessage(BitcoinMessages.pong(new PongMessage(42)));
                assertFalse(peer.hasOutstandingPing());
            });
        } finally {
            release.countDown();
        }
        assertTrue(send.get(2, TimeUnit.SECONDS));
        assertFalse(peer.hasOutstandingPing());
        assertTrue(peer.lastPingRoundTrip().isPresent());
    }
}
