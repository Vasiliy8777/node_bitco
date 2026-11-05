package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.net.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AddrPermissionTest {
    @Test void addrPermissionStillRestrictsGetAddrToInboundOnce() throws Exception {
        var protocol = new PeerAddressProtocol(new PeerAddressManager());
        var access = new PeerAccess(Set.of(PeerPermission.ADDR), false);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var remote = new PeerConnection(NetworkParametersRegistry.regtest());
             var inboundConnection = new PeerConnection(NetworkParametersRegistry.regtest());
             var inbound = new Peer(inboundConnection, 1, 0, true, access)) {
            remote.connect(server.getInetAddress().getHostAddress(), server.getLocalPort());
            inbound.accept(server.accept());
            protocol.onMessage(inbound, BitcoinMessages.getAddr());
            assertTrue(inbound.getAddrReceived());
            protocol.onMessage(inbound, BitcoinMessages.getAddr());
            assertFalse(inbound.markGetAddrReceived(), "Permission must not reset the one-response guard");
        }
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var connection = new PeerConnection(NetworkParametersRegistry.regtest());
             var outbound = new Peer(connection, 1, 0, true, access)) {
            outbound.connect(server.getInetAddress().getHostAddress(), server.getLocalPort());
            try (var accepted = server.accept()) {
                protocol.onMessage(outbound, BitcoinMessages.getAddr());
                assertFalse(outbound.getAddrReceived(), "Outbound GETADDR must be ignored even with addr permission");
            }
        }
    }

    @Test void onlyAddrPermissionBypassesSharedAddrAndAddrV2Budget() throws Exception {
        assertEquals(1, receive(Set.of()));
        assertEquals(1, receive(Set.of(PeerPermission.NOBAN)));
        assertEquals(1, receive(Set.of(PeerPermission.DOWNLOAD)));
        assertEquals(3, receive(Set.of(PeerPermission.ADDR)));
    }

    private static int receive(Set<PeerPermission> permissions) throws Exception {
        var manager = new PeerAddressManager();
        var protocol = new PeerAddressProtocol(manager, null, () -> 0L);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var connection = new PeerConnection(NetworkParametersRegistry.regtest());
             var peer = new Peer(connection, 1, 0, true, new PeerAccess(permissions, false))) {
            peer.connect(server.getInetAddress().getHostAddress(), server.getLocalPort());
            try (var accepted = server.accept()) {
                long now = Instant.now().getEpochSecond();
                protocol.onMessage(peer, BitcoinMessages.addr(new AddrMessage(List.of(
                        AddrEntry.fromIp(now, 1, InetAddress.getByName("8.8.8.8"), 8333),
                        AddrEntry.fromIp(now, 1, InetAddress.getByName("1.1.1.1"), 8333)))));
                protocol.onMessage(peer, BitcoinMessages.addrV2(new AddrV2Message(List.of(
                        new AddrV2Entry(now, 1, AddrV2Network.IPV4.id(), InetAddress.getByName("9.9.9.9").getAddress(), 8333)))));
                assertEquals(permissions.contains(PeerPermission.ADDR), peer.hasPermission(PeerPermission.ADDR));
                return manager.size();
            }
        }
    }
}
