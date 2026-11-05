package ru.bitcoin.node.p2p;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeerDiscouragementPolicyTest {
    @ParameterizedTest
    @CsvSource({"203.0.113.9,false,false,true", "127.0.0.1,false,false,false", "0.1.2.3,false,false,false",
            "::1,false,false,false", "2001:db8::1,false,false,true", "203.0.113.9,true,false,false", "203.0.113.9,false,true,false"})
    void closePolicyDistinguishesLocalManualAndNobanAddresses(String address, boolean noBan, boolean manual, boolean discouraged) throws Exception {
        var peer = mock(Peer.class); var listener = new AtomicReference<PeerCloseListener>();
        var endpoint = new InetSocketAddress(InetAddress.getByName(address), 8333);
        when(peer.remoteAddress()).thenReturn(endpoint);
        when(peer.hasPermission(PeerPermission.NOBAN)).thenReturn(noBan);
        when(peer.isManualConnection()).thenReturn(manual);
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(peer).addCloseListener(any());
        try (var peers = new PeerManager()) {
            peers.add(peer, manual ? PeerConnectionRole.MANUAL : PeerConnectionRole.FULL_RELAY);
            listener.get().onPeerClosed(peer, new IOException("Invalid message", new PeerProtocolException("Invalid message")));
            assertTrue(peers.isEmpty());
            assertEquals(discouraged, peers.discouragementManager().isDiscouraged(endpoint.getAddress()));
        }
    }
}
