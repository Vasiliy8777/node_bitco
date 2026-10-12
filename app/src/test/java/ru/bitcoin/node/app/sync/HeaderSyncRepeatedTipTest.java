package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HeaderSyncRepeatedTipTest {
    @Test
    void validatedKnownTipAnnouncementEndsRoundWithoutFailingNode() throws Exception {
        var tip = index(1);
        var wire = mock(HeadersMessage.class);
        when(wire.isEmpty()).thenReturn(false);
        var transport = mock(HeaderSynchronizer.class);
        when(transport.download(anyList(), any())).thenReturn(wire);
        var service = mock(HeaderSyncService.class);
        when(service.process(eq(wire), any())).thenReturn(List.of(tip));
        var locator = mock(BlockLocatorBuilder.class);
        var hash = tip.hash();
        when(locator.build(any())).thenReturn(List.of(hash));
        var coordinator = new HeaderSyncCoordinator(transport, service, new HeaderChainState(tip), locator);
        assertEquals(List.of(), coordinator.synchronize(new Hash256(new byte[32])));
        verify(service).process(eq(wire), any());
        verify(transport).download(anyList(), any());
    }

    @Test
    void keepsNewHeadersWhenFollowedByDuplicateTip() throws Exception {
        var first = index(1);
        var next = index(2);
        var wire = mock(HeadersMessage.class);
        var transport = mock(HeaderSynchronizer.class);
        when(transport.download(anyList(), any())).thenReturn(wire);
        var service = mock(HeaderSyncService.class);
        when(service.process(eq(wire), any())).thenReturn(List.of(next));
        var locator = mock(BlockLocatorBuilder.class);
        var hash = first.hash();
        when(locator.build(any())).thenReturn(List.of(hash));
        var coordinator = new HeaderSyncCoordinator(transport, service, new HeaderChainState(first), locator);
        assertEquals(List.of(next), coordinator.synchronize(new Hash256(new byte[32])));
        verify(transport, times(2)).download(anyList(), any());
    }

    @Test
    void cyclicRepliesAreRecoverablePeerFailureRatherThanFatalNodeFailure() throws Exception {
        var first = index(1);
        var next = index(2);
        var wire = mock(HeadersMessage.class);
        var transport = mock(HeaderSynchronizer.class);
        when(transport.download(anyList(), any())).thenReturn(wire);
        var service = mock(HeaderSyncService.class);
        when(service.process(eq(wire), any())).thenReturn(List.of(next), List.of(first));
        var locator = mock(BlockLocatorBuilder.class);
        var hash = first.hash();
        when(locator.build(any())).thenReturn(List.of(hash));
        var coordinator = new HeaderSyncCoordinator(transport, service, new HeaderChainState(first), locator);
        assertThrows(IOException.class, () -> coordinator.synchronize(new Hash256(new byte[32])));
        verify(transport, times(2)).download(anyList(), any());
    }

    private static BlockIndex index(int value) {
        var index = mock(BlockIndex.class);
        when(index.hash()).thenReturn(Hash256.fromDisplayHex(String.format("%064x", value)));
        return index;
    }
}
