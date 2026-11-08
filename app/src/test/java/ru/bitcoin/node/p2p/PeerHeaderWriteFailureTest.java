package ru.bitcoin.node.p2p;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeerHeaderWriteFailureTest {
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void transportCloseRaceIsIoButConnectedInternalFailureRemainsFatal(boolean connected, boolean asynchronous) throws Exception {
        var connection = mock(PeerConnection.class);
        when(connection.isConnected()).thenReturn(connected);
        var failure = new IllegalStateException("Synthetic writer state failure");
        if (asynchronous) doThrow(failure).when(connection).sendAsync(any(), any());
        else doThrow(failure).when(connection).send(any());
        try (var peer = new Peer(connection, VersionMessage.DEFAULT_SERVICES, 0, true)) {
            // Freeze the interval after transport close but before reader cleanup.
            var state = Peer.class.getDeclaredField("state");
            state.setAccessible(true); state.set(peer, PeerState.READY);
            var request = new GetHeadersMessage(70016, List.of(new Hash256(new byte[32])), new Hash256(new byte[32]));
            if (connected) {
                assertSame(failure, assertThrows(IllegalStateException.class, () -> {
                    if (asynchronous) peer.trySendGetHeadersAsync(request); else peer.trySendGetHeaders(request);
                }));
            } else {
                var reported = assertThrows(IOException.class, () -> {
                    if (asynchronous) peer.trySendGetHeadersAsync(request); else peer.trySendGetHeaders(request);
                });
                assertSame(failure, reported.getCause());
            }
            when(connection.isConnected()).thenReturn(true);
            if (asynchronous) doNothing().when(connection).sendAsync(any(), any());
            else doNothing().when(connection).send(any());
            assertTrue(asynchronous ? peer.trySendGetHeadersAsync(request) : peer.trySendGetHeaders(request),
                    "Failed writes must release the request gate");
        }
    }
}
