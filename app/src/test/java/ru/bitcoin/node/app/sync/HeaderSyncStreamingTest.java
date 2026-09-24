package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeaderSyncStreamingTest {
    @Test
    void deliversEachBatchBeforeDownloadingNextAndUsesLastProcessedBranchCursor() throws Exception {
        var downloader = mock(HeaderSynchronizer.class);
        var processor = mock(HeaderSyncService.class);
        var state = mock(HeaderChainState.class);
        var locator = mock(BlockLocatorBuilder.class);
        var start = index(0); var first = index(1); var second = index(2);
        when(state.bestHeaderTip()).thenReturn(start);
        when(locator.build(any())).thenAnswer(call -> List.of(((BlockIndex) call.getArgument(0)).hash()));
        var header = GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header();
        var message = new HeadersMessage(List.of(header));
        var delivered = new ArrayList<Hash256>();
        when(downloader.download(anyList(), any())).thenAnswer(call -> {
            List<Hash256> current = call.getArgument(0);
            if (current.equals(List.of(start.hash()))) {
                assertTrue(delivered.isEmpty()); return message;
            }
            if (current.equals(List.of(first.hash()))) {
                assertEquals(List.of(first.hash()), delivered); return message;
            }
            assertEquals(List.of(first.hash(), second.hash()), delivered);
            assertEquals(List.of(second.hash()), current);
            return new HeadersMessage(List.of());
        });
        when(processor.process(message)).thenReturn(List.of(first), List.of(second));
        var coordinator = new HeaderSyncCoordinator(downloader, processor, state, locator);
        assertEquals(2, coordinator.synchronize(start.hash(), batch -> {
            assertThrows(UnsupportedOperationException.class, () -> batch.add(start));
            delivered.add(batch.getLast().hash());
        }));
        verify(state, times(1)).bestHeaderTip();
    }

    @Test
    void nonProgressingResponseIsRejectedBeforePublishingBatch() throws Exception {
        var downloader = mock(HeaderSynchronizer.class);
        var processor = mock(HeaderSyncService.class);
        var state = mock(HeaderChainState.class);
        var locator = mock(BlockLocatorBuilder.class);
        var start = index(0);
        when(state.bestHeaderTip()).thenReturn(start);
        var startLocator = List.of(start.hash());
        when(locator.build(start)).thenReturn(startLocator);
        var message = new HeadersMessage(List.of(GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header()));
        when(downloader.download(anyList(), any())).thenReturn(message);
        when(processor.process(message)).thenReturn(List.of(start));
        var coordinator = new HeaderSyncCoordinator(downloader, processor, state, locator);
        assertThrows(IllegalStateException.class, () -> coordinator.synchronize(start.hash(),
                batch -> fail("Non-progressing batch must not be published")));
        verify(downloader, times(1)).download(anyList(), any());
    }

    private static BlockIndex index(int value) {
        var index = mock(BlockIndex.class);
        byte[] bytes = new byte[32]; bytes[0] = (byte) value;
        when(index.hash()).thenReturn(new Hash256(bytes));
        return index;
    }
}
