package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.AddrV2Entry;
import ru.bitcoin.node.p2p.message.AddrV2Network;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PeerAddressBip155Test {

    @Test
    void convertsEverySupportedBip155NetworkIntoAddrManModel() throws Exception {
        assertNetwork(AddrV2Network.IPV4, InetAddress.getByName("192.0.2.1").getAddress(), PeerAddressNetwork.IPV4);
        assertNetwork(AddrV2Network.IPV6, InetAddress.getByName("2001:db8::1").getAddress(), PeerAddressNetwork.IPV6);
        assertNetwork(AddrV2Network.TORV3, bytes(32, 1), PeerAddressNetwork.TORV3);
        assertNetwork(AddrV2Network.I2P, bytes(32, 33), PeerAddressNetwork.I2P);
        byte[] cjdns = bytes(16, 65); cjdns[0] = (byte) 0xfc;
        assertNetwork(AddrV2Network.CJDNS, cjdns, PeerAddressNetwork.CJDNS);
    }

    @Test
    void rejectsTorV2AndNonCjdnsPrefix() {
        assertNull(PeerAddressProtocol.toPeerAddress(new AddrV2Entry(1, 1, AddrV2Network.TORV2.id(), new byte[10], 8333)));
        byte[] invalid = new byte[16]; invalid[0] = (byte) 0xfd;
        assertNull(PeerAddressProtocol.toPeerAddress(new AddrV2Entry(1, 1, AddrV2Network.CJDNS.id(), invalid, 8333)));
    }

    @Test
    void addrManRetainsPrivacyNetworksButDoesNotSelectThemForDirectSocketDialing() {
        PeerAddressManager manager = new PeerAddressManager();
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        PeerAddress tor = new PeerAddress(PeerAddressNetwork.TORV3, bytes(32, 7), 8333, 1);
        PeerAddress i2p = new PeerAddress(PeerAddressNetwork.I2P, bytes(32, 17), 8333, 1);
        manager.add(tor, now);
        manager.add(i2p, now);
        assertEquals(2, manager.size());
        assertTrue(manager.select(Set.of()).isEmpty(), "Tor/I2P need proxy transports and must not be passed to a direct TCP connector");
    }

    @Test
    void addrv1CompatibilityIsLimitedToIpv4AndIpv6() throws Exception {
        assertTrue(new PeerAddress(InetAddress.getByName("192.0.2.1"), 8333, 1).isLegacyAddrCompatible());
        assertTrue(new PeerAddress(InetAddress.getByName("2001:db8::1"), 8333, 1).isLegacyAddrCompatible());
        assertFalse(new PeerAddress(PeerAddressNetwork.TORV3, bytes(32, 1), 8333, 1).isLegacyAddrCompatible());
        assertFalse(new PeerAddress(PeerAddressNetwork.I2P, bytes(32, 1), 8333, 1).isLegacyAddrCompatible());
        byte[] cjdns = new byte[16]; cjdns[0] = (byte) 0xfc;
        assertFalse(new PeerAddress(PeerAddressNetwork.CJDNS, cjdns, 8333, 1).isLegacyAddrCompatible());
    }

    @Test
    void endpointIdentityIncludesNetworkAndRawBytes() {
        byte[] raw = bytes(32, 9);
        PeerAddress a = new PeerAddress(PeerAddressNetwork.TORV3, raw, 8333, 1);
        PeerAddress b = new PeerAddress(PeerAddressNetwork.TORV3, raw, 8333, 99);
        PeerAddressManager manager = new PeerAddressManager();
        manager.add(a, Instant.ofEpochSecond(1));
        manager.add(b, Instant.ofEpochSecond(2));
        assertEquals(1, manager.size(), "services updates must not create a second endpoint");
        assertEquals(99, manager.addresses().get(0).peerAddress().services());
    }

    private static void assertNetwork(AddrV2Network wire, byte[] raw, PeerAddressNetwork expected) {
        PeerAddress address = PeerAddressProtocol.toPeerAddress(new AddrV2Entry(1, 9, wire.id(), raw, 8333));
        assertNotNull(address);
        assertEquals(expected, address.network());
        assertArrayEquals(raw, address.rawAddress());
        assertEquals(8333, address.port());
        assertEquals(9, address.services());
    }

    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) result[i] = (byte) (seed + i);
        return result;
    }
}
