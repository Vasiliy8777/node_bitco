package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PeerBanManagerTest {
    @TempDir
    Path directory;

    @Test
    void persistsIpv4SubnetAndReloadsIt() throws Exception {
        var manager = new PeerBanManager(directory);
        manager.ban("192.0.2.99/24", 3600, false);
        assertTrue(manager.isBanned(InetAddress.getByName("192.0.2.7")));
        assertFalse(manager.isBanned(InetAddress.getByName("192.0.3.7")));
        assertEquals("192.0.2.0/24", manager.entries().getFirst().subnet());
        var restored = new PeerBanManager(directory);
        assertTrue(restored.isBanned(InetAddress.getByName("192.0.2.200")));
        assertTrue(restored.unban("192.0.2.1/24"));
        assertFalse(new PeerBanManager(directory).isBanned(InetAddress.getByName("192.0.2.200")));
    }

    @Test
    void supportsIpv6AndClear() throws Exception {
        var manager = new PeerBanManager(directory);
        manager.ban("2001:db8::/32", 3600, false);
        assertTrue(manager.isBanned(InetAddress.getByName("2001:db8:1::1")));
        assertFalse(manager.isBanned(InetAddress.getByName("2001:db9::1")));
        manager.clear();
        assertTrue(new PeerBanManager(directory).entries().isEmpty());
    }
}
