package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkAddressTest {

    @Test
    void shouldConvertIpv4ToIpv4MappedIpv6()
            throws Exception {

        NetworkAddress address =
                NetworkAddress.fromIp(
                        1,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        byte[] expected =
                new byte[]{
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0,
                        (byte) 0xff,
                        (byte) 0xff,
                        127, 0, 0, 1
                };

        assertArrayEquals(
                expected,
                address.address()
        );

        assertEquals(
                8333,
                address.port()
        );
    }
}