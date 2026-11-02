package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvictionNetGroupTest {
    @Test void linkedIpv4TransportsShareTheirIpv4Group() throws Exception {
        for (var host : List.of("8.8.8.8", "8.8.1.1", "::ffff:8.8.8.8", "64:ff9b::808:808",
                "2002:808:808::1", "2001:0:0:0:0:0:f7f7:f7f7")) assertGroup(host, "010808");
        assertGroup("9.8.8.8", "010908");
    }
    @Test void ipv6UsesSlash32ExceptHeNetSlash36() throws Exception {
        assertGroup("2606:4700::1111", "0226064700");
        assertGroup("2001:470:1234::1", "02200104701f");
        assertGroup("2001:470:1abc::1", "02200104701f");
        assertGroup("2001:470:2000::1", "02200104702f");
    }
    @Test void unroutableAndLocalPeersCannotInventDistinctNetgroups() throws Exception {
        for (var host : List.of("127.0.0.1", "127.0.0.2", "0.1.2.3", "::1", "10.0.0.1", "100.64.0.1",
                "192.0.2.1", "198.18.0.1", "2001:db8::1", "3fff::1", "3fff:fff::1", "fc01::1")) assertGroup(host, "00");
        assertTrue(EvictionNetGroup.local(InetAddress.getByName("0.1.2.3")));
        assertGroup("100.128.0.1", "016480");
        assertGroup("3fff:1000::1", "023fff1000");
    }
    private static void assertGroup(String host, String expected) throws Exception {
        assertEquals(expected, HexFormat.of().formatHex(EvictionNetGroup.group(InetAddress.getByName(host))), host);
    }
}
