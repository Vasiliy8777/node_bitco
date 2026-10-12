package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.HeaderChainState;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;

import java.math.BigInteger;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeLifecycleCatchUpTest {
    @Test
    void catchesUpWhenHeadersAdvanceDuringEachDownloadRound() throws Exception {
        var first = index(100);
        var second = index(105);
        var third = index(106);
        var fourth = index(107);
        var active = new AtomicReference<>(first);
        var best = new AtomicReference<>(second);
        var validation = mock(NodeValidationService.class);
        when(validation.activeTip()).thenAnswer(call -> active.get());
        var headers = mock(HeaderChainState.class);
        when(headers.bestHeaderTip()).thenAnswer(call -> best.get());
        var infrastructure = mock(NodeSyncInfrastructure.class);
        when(infrastructure.headerChainState()).thenReturn(headers);
        var blocks = mock(BlockSyncCoordinator.class);
        doAnswer(call -> {
            active.set(second);
            best.set(third);
            return null;
        }).doAnswer(call -> {
            active.set(third);
            best.set(fourth);
            return null;
        }).doAnswer(call -> {
            active.set(fourth);
            return null;
        }).when(blocks).synchronizeToTip();
        var lifecycle = lifecycle(validation, infrastructure, blocks, BigInteger.ZERO);
        var synchronize = NodeLifecycleService.class.getDeclaredMethod("synchronizeBlocks");
        synchronize.setAccessible(true);
        synchronize.invoke(lifecycle);
        assertEquals(best.get().hash(), active.get().hash());
        verify(blocks, times(3)).synchronizeToTip();
        assertTrue(lifecycle.failure().isEmpty());
        assertFalse(lifecycle.isMiningReady());
    }

    @Test
    void rejectsNoProgressInsteadOfSpinningWhenHeaderTargetIsUnchanged() throws Exception {
        var active = index(100);
        var best = index(105);
        var validation = mock(NodeValidationService.class);
        when(validation.activeTip()).thenReturn(active);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        var headers = mock(HeaderChainState.class);
        when(headers.bestHeaderTip()).thenReturn(best);
        when(infrastructure.headerChainState()).thenReturn(headers);
        var blocks = mock(BlockSyncCoordinator.class);
        var lifecycle = lifecycle(validation, infrastructure, blocks, BigInteger.ZERO);
        var synchronize = NodeLifecycleService.class.getDeclaredMethod("synchronizeBlocks");
        synchronize.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> synchronize.invoke(lifecycle));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("made no progress"));
        verify(blocks).synchronizeToTip();
    }

    @Test
    void stillRejectsSynchronizedTipBelowMinimumChainWork() throws Exception {
        var tip = index(100);
        var validation = mock(NodeValidationService.class);
        when(validation.activeTip()).thenReturn(tip);
        var infrastructure = mock(NodeSyncInfrastructure.class);
        var headers = mock(HeaderChainState.class);
        when(headers.bestHeaderTip()).thenReturn(tip);
        when(infrastructure.headerChainState()).thenReturn(headers);
        var blocks = mock(BlockSyncCoordinator.class);
        var lifecycle = lifecycle(validation, infrastructure, blocks, BigInteger.valueOf(101));
        var synchronize = NodeLifecycleService.class.getDeclaredMethod("synchronizeBlocks");
        synchronize.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> synchronize.invoke(lifecycle));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("below minimum chain work"));
        verify(blocks).synchronizeToTip();
    }

    private static BlockIndex index(int height) {
        var index = mock(BlockIndex.class);
        when(index.height()).thenReturn((long) height);
        when(index.hash()).thenReturn(Hash256.fromDisplayHex(String.format("%064x", height)));
        when(index.chainWork()).thenReturn(BigInteger.valueOf(height));
        return index;
    }

    private static NodeLifecycleService lifecycle(NodeValidationService validation,
            NodeSyncInfrastructure infrastructure, BlockSyncCoordinator blocks, BigInteger minimumWork) {
        return new NodeLifecycleService(validation, infrastructure, new PeerAddressManager(),
                mock(PeerDiscovery.class), mock(OutboundPeerManager.class), mock(OutboundPeerSupervisor.class),
                mock(PeerManager.class), mock(BitcoinServer.class), blocks,
                Duration.ofSeconds(1), false, 18444, minimumWork);
    }
}
