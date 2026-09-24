package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AddressRelayBudgetTest {
    @Test
    void bothAddressFormatsShareQuotaWhileAnotherPeerKeepsItsOwnBudget() throws Exception {
        var manager = mock(PeerAddressManager.class);
        var time = new AtomicLong();
        var protocol = new PeerAddressProtocol(manager, null, time::get);
        var first = peer(1); var second = peer(2);
        var address = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        var legacy = AddrEntry.fromIp(1_700_000_000L, 9L, address, 8333);
        var v2 = new AddrV2Entry(1_700_000_000L, 9L, AddrV2Network.IPV4.id(), address.getAddress(), 8333);
        var burst = BitcoinMessages.addr(new AddrMessage(Collections.nCopies(1000, legacy)));
        var newer = BitcoinMessages.addrV2(new AddrV2Message(List.of(v2)));
        protocol.onMessage(first, burst);
        protocol.onMessage(first, newer);
        verify(manager, times(1000)).add(any(PeerAddress.class), any(PeerAddressSource.class), any(Instant.class));
        time.set(1_000_000_000L);
        protocol.onMessage(first, newer);
        protocol.onMessage(second, burst);
        verify(manager, times(2001)).add(any(PeerAddress.class), any(PeerAddressSource.class), any(Instant.class));
        verify(first, never()).close();
    }

    @Test
    void messageFloodIsDroppedBeforeDecodingAndBudgetRecovers() throws Exception {
        var time = new AtomicLong();
        var protocol = new PeerAddressProtocol(mock(PeerAddressManager.class), null, time::get);
        var peer = peer(1);
        for (int i = 0; i < 10; i++) protocol.onMessage(peer, BitcoinMessages.addr(new AddrMessage(List.of())));
        var malformed = new BitcoinMessage("addrv2", new byte[0]);
        for (int i = 0; i < 1000; i++) assertDoesNotThrow(() -> protocol.onMessage(peer, malformed));
        time.set(1_000_000_000L);
        assertThrows(PeerAddressProtocolException.class, () -> protocol.onMessage(peer, malformed));
    }

    @Test
    void fractionalRefillAndClockWrapDoNotResetBurst() {
        long start = Long.MAX_VALUE - 10;
        var budget = new AddressRelayBudget(start);
        assertEquals(1000, budget.takeAddresses(1000, start));
        assertEquals(0, budget.takeAddresses(1, start + 500_000_000L));
        assertEquals(1, budget.takeAddresses(2, start + 1_000_000_000L));
        assertEquals(0, budget.takeAddresses(1, start + 1_000_000_000L));
        assertEquals(1000, budget.takeAddresses(2000, start + 10_000_000_000_000L));
    }

    private static Peer peer(int last) throws Exception {
        var peer = mock(Peer.class);
        when(peer.remoteAddress()).thenReturn(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, (byte) last}), 8333));
        return peer;
    }
}