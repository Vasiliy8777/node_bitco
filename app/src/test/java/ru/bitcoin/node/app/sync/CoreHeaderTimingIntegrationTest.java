package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.*;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.block.BlockHeader;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreHeaderTimingIntegrationTest {
    @Test
    void competingAvailabilitySendCannotStealAnUncommittedInitialReservation() throws Exception {
        var tip = mock(BlockIndex.class); var header = mock(BlockHeader.class);
        when(header.timestamp()).thenReturn(new UInt32(1)); when(tip.header()).thenReturn(header);
        var headers = new HeaderChainState(tip);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var request = new GetHeadersMessage(70016, List.of(Hash256.fromDisplayHex("11".repeat(32))), new Hash256(new byte[32]));
        try (var peers = new PeerManager(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = peer(); peers.add(peer);
            when(peer.trySendGetHeaders(any())).thenAnswer(call -> {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return false;
            });
            when(peer.trySendGetHeadersAsync(any())).thenReturn(true);
            var initial = new CoreHeadersResponseControl(peer, peers, headers, 600, () -> false);
            var availability = new CoreHeadersResponseControl(peer, peers, headers, 600, () -> false);
            var first = workers.submit(() -> initial.trySend(request));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertFalse(availability.trySendAsync(request)));
                verify(peer, never()).trySendGetHeadersAsync(any());
            } finally { release.countDown(); }
            assertFalse(first.get(2, TimeUnit.SECONDS));
            assertEquals(0, peers.headersSyncStartedCount());
            assertTrue(availability.trySendAsync(request));
            assertEquals(1, peers.headersSyncStartedCount());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 30})
    void initialLocatorStartsBeforeBestHeaderAndGateRetryKeepsInitialization(int height) throws Exception {
        var indexes = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = null;
        for (int index = 0; index <= height; index++) {
            var hash = Hash256.fromDisplayHex(String.format("%064x", index + 1));
            var header = mock(BlockHeader.class);
            when(header.timestamp()).thenReturn(new UInt32(1));
            tip = new BlockIndex(hash, header, index, tip == null ? new Hash256(new byte[32]) : tip.hash(),
                    java.math.BigInteger.valueOf(index + 1));
            indexes.put(hash, tip);
        }
        var chain = new HeaderChainState(tip);
        var locators = new BlockLocatorBuilder(indexes::get);
        var request = new GetHeadersMessage(70015, locators.build(tip), Hash256.fromDisplayHex("ff".repeat(32)));
        var sent = new ArrayList<GetHeadersMessage>();
        var allowed = new AtomicBoolean();
        try (var peers = new PeerManager()) {
            var peer = peer(); peers.add(peer);
            when(peer.trySendGetHeaders(any())).thenAnswer(call -> {
                sent.add(call.getArgument(0)); return allowed.get();
            });
            var control = new CoreHeadersResponseControl(peer, peers, chain, 600, () -> false,
                    () -> 1_000_000_000_000L, locators);
            assertFalse(control.trySend(request));
            assertEquals(0, peers.headersSyncStartedCount(), "Rejected transport reservation must be rolled back");
            allowed.set(true);
            assertTrue(control.trySend(request));
            var expectedHeights = height == 0 ? List.of(0L)
                    : List.of(29L, 28L, 27L, 26L, 25L, 24L, 23L, 22L, 21L, 20L, 19L, 18L, 16L, 12L, 4L, 0L);
            for (var first : sent) {
                assertEquals(expectedHeights, first.locatorHashes().stream().map(indexes::get).map(BlockIndex::height).toList());
                assertEquals(70015, first.protocolVersion()); assertEquals(request.stopHash(), first.stopHash());
            }
            assertTrue(control.trySend(request));
            assertSame(request, sent.getLast(), "Continuation must retain its caller's locator");
            assertEquals(1, peers.headersSyncStartedCount());
        }
    }
    @Test
    void supervisorInitialRequestsDoNotBypassCoordinatorAdmission() throws Exception {
        var tip = mock(BlockIndex.class);
        var header = mock(BlockHeader.class);
        when(header.timestamp()).thenReturn(new UInt32(1));
        when(tip.header()).thenReturn(header);
        var headers = new HeaderChainState(tip);
        var validation = mock(NodeValidationService.class);
        when(validation.downloadInitialBlockDownload()).thenReturn(true);
        var sync = mock(NodeSyncInfrastructure.class);
        var locator = List.of(Hash256.fromDisplayHex("11".repeat(32)));
        var request = new GetHeadersMessage(VersionMessage.CURRENT_PROTOCOL_VERSION, locator, new Hash256(new byte[32]));
        try (var peers = new PeerManager();
             var relay = new ru.bitcoin.node.app.service.NodeRelayService(validation, sync, peers)) {
            var first = peer(); var second = peer();
            peers.add(first); peers.add(second);
            when(first.trySendGetHeaders(any())).thenReturn(true);
            when(second.trySendGetHeaders(any())).thenReturn(true);
            var recovery = relay.outboundRecoveryView();
            assertFalse(recovery.headersSyncStarted(first));
            assertFalse(recovery.headersSyncStarted(second));
            recovery.requestInitialHeaders(first, locator);
            recovery.requestInitialHeaders(second, locator);
            verify(first, never()).sendAsync(argThat(message -> message.command().equals("getheaders")));
            verify(second, never()).sendAsync(argThat(message -> message.command().equals("getheaders")));
            assertEquals(0, peers.headersSyncStartedCount());
            var firstControl = new CoreHeadersResponseControl(first, peers, headers, 600, () -> false);
            var secondControl = new CoreHeadersResponseControl(second, peers, headers, 600, () -> false);
            assertTrue(firstControl.trySend(request));
            assertTrue(recovery.headersSyncStarted(first));
            assertFalse(recovery.headersSyncStarted(second));
            assertFalse(secondControl.trySend(request), "Old tip permits only one initial source");
            assertEquals(1, peers.headersSyncStartedCount());
            // The distinct chainwork catch-up probe must retain its own path.
            recovery.requestHeaders(second, locator);
            verify(second).sendAsync(argThat(message -> message.command().equals("getheaders")));
            peers.remove(first);
            assertFalse(recovery.headersSyncStarted(first));
            assertTrue(secondControl.trySend(request));
            assertTrue(recovery.headersSyncStarted(second));
            assertEquals(1, peers.headersSyncStartedCount());
        }
    }
    @Test
    void explicitLegacyTimeoutRetainsChainworkChecksWithoutCoreTimerState() throws Exception {
        try (var peers = new PeerManager();
             var relay = new ru.bitcoin.node.app.service.NodeRelayService(mock(NodeValidationService.class),
                     mock(NodeSyncInfrastructure.class), peers, null, 14, false, false)) {
            var peer = peer();
            assertFalse(peers.headersSyncStarted(peer));
            assertTrue(relay.outboundRecoveryView().headersSyncStarted(peer));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void controlledClockWaiterExpiresOnlyWithAlternativeAndNobanResets(boolean noBan) throws Exception {
        var tip=mock(BlockIndex.class);var header=mock(BlockHeader.class);when(header.timestamp()).thenReturn(new UInt32(1));when(tip.header()).thenReturn(header);
        var headers=new HeaderChainState(tip);var clock=new AtomicLong(1_000_000_000_000L);
        try(var peers=new PeerManager();var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var peer=peer();when(peer.hasPermission(PeerPermission.NOBAN)).thenReturn(noBan);peers.add(peer);
            var dispatcher=new PeerMessageDispatcher(peer);when(peer.messageDispatcher()).thenReturn(dispatcher);
            var sent=new CountDownLatch(1);doAnswer(call->{sent.countDown();return true;}).when(peer).trySendGetHeaders(any());
            var control=new CoreHeadersResponseControl(peer,peers,headers,600,()->false,clock::get);
            var download=workers.submit(()->new HeaderSynchronizer(peer,HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT,control)
                    .download(List.of(Hash256.fromDisplayHex("11".repeat(32))),new Hash256(new byte[32])));
            try {
                assertTrue(sent.await(3,TimeUnit.SECONDS));
                long deadline=HeadersSyncTimeoutPolicy.initialDeadline(clock.get(),clock.get()/1_000_000,1,600);
                clock.set(deadline);assertDoesNotThrow(control::waiting);
                clock.incrementAndGet();assertDoesNotThrow(control::waiting);assertFalse(download.isDone());
                peers.add(peer());
                var failure=assertThrows(ExecutionException.class,()->download.get(3,TimeUnit.SECONDS));
                assertInstanceOf(HeaderSynchronizationTimeoutException.class,failure.getCause());
                assertEquals(noBan?0:1,peers.headersSyncStartedCount());
                verify(peer,never()).headersResponseValidated();verify(peer,never()).close();
            } finally {download.cancel(true);workers.shutdownNow();}
        }
    }
    @Test void silentCaughtUpPeerDoesNotBlockHealthyPeerOrGetDisconnected() throws Exception {
        var tip=mock(BlockIndex.class); var hash=Hash256.fromDisplayHex("11".repeat(32)); when(tip.hash()).thenReturn(hash);
        var header=mock(BlockHeader.class); when(header.timestamp()).thenReturn(new UInt32(Instant.now().getEpochSecond())); when(tip.header()).thenReturn(header);
        var headers=new HeaderChainState(tip); var infrastructure=mock(NodeSyncInfrastructure.class);
        when(infrastructure.headerChainState()).thenReturn(headers);
        var locator=mock(BlockLocatorBuilder.class); when(locator.build(any())).thenReturn(List.of(hash)); when(infrastructure.blockLocatorBuilder()).thenReturn(locator);
        var service=mock(HeaderSyncService.class); when(infrastructure.headerSyncService()).thenReturn(service);
        doAnswer(call -> { ((Runnable)call.getArgument(3)).run(); return null; }).when(service).processPeerWithReception(any(),any(),any(),any());
        var validation=mock(NodeValidationService.class); when(validation.activeTip()).thenReturn(tip);
        var blocks=mock(BlockSyncCoordinator.class); var healthyResponse=new CountDownLatch(1); var silentRequest=new CountDownLatch(1);
        try(var peers=new PeerManager()) {
            var silent=peer(); var healthy=peer(); peers.add(silent); peers.add(healthy);
            var silentDispatcher=new PeerMessageDispatcher(silent); when(silent.messageDispatcher()).thenReturn(silentDispatcher);
            var healthyDispatcher=new PeerMessageDispatcher(healthy); when(healthy.messageDispatcher()).thenReturn(healthyDispatcher);
            doAnswer(call -> { silentRequest.countDown(); return true; }).when(silent).trySendGetHeaders(any());
            doAnswer(call -> { healthyDispatcher.dispatch(BitcoinMessages.headers(new HeadersMessage(List.of()))); healthyResponse.countDown(); return true; })
                    .when(healthy).trySendGetHeaders(any());
            when(infrastructure.headerSynchronizer(any(),any(),any(),any())).thenAnswer(call -> new HeaderSynchronizer(call.getArgument(0),
                    HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT,new CoreHeadersResponseControl(call.getArgument(0),peers,headers,600,()->false)));
            var live=new LiveChainSynchronizer(peers,infrastructure,blocks,validation,HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT);
            var failure=new AtomicReference<Throwable>(); var worker=Thread.ofPlatform().daemon().start(()->{try{live.run();}catch(Throwable error){failure.set(error);}});
            try {
                assertTrue(silentRequest.await(3,TimeUnit.SECONDS)); assertTrue(healthyResponse.await(3,TimeUnit.SECONDS));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!live.isCurrent()&&failure.get()==null&&System.nanoTime()<end) Thread.sleep(5);
                assertTrue(live.isCurrent(),()->String.valueOf(failure.get())); assertEquals(2,peers.headersSyncStartedCount());
                verify(silent,never()).close(); verify(blocks,atLeastOnce()).synchronizeToTip();
            } finally {live.close();worker.interrupt();worker.join(3000);}
            assertFalse(worker.isAlive()); assertNull(failure.get());
        }
    }
    private static Peer peer() {
        var peer=mock(Peer.class);when(peer.isReady()).thenReturn(true);when(peer.state()).thenReturn(PeerState.READY);
        var version=mock(VersionMessage.class);when(version.version()).thenReturn(VersionMessage.CURRENT_PROTOCOL_VERSION);
        when(version.services()).thenReturn(VersionMessage.DEFAULT_SERVICES);when(peer.remoteVersion()).thenReturn(version);return peer;
    }
}
