package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PeerPermissionPolicyTest {
    @Test void mempoolPermissionIsIndependentOfRelayAndNoBan() throws Exception {
        assertEquals(Set.of(PeerPermission.MEMPOOL), PeerPermissionPolicy.parse("mempool@127.0.0.1")
                .accessFor(InetAddress.getByName("127.0.0.1")).permissions());
    }
    @Test void forceRelayIncludesRelayButNeitherGrantsUnrelatedPermissions() throws Exception {
        var address = InetAddress.getByName("127.0.0.1");
        assertEquals(Set.of(PeerPermission.FORCERELAY, PeerPermission.RELAY),
                PeerPermissionPolicy.parse("forcerelay@127.0.0.1").accessFor(address).permissions());
        assertEquals(Set.of(PeerPermission.RELAY),
                PeerPermissionPolicy.parse("relay@127.0.0.1").accessFor(address).permissions());
    }
    @Test void addrPermissionIsExplicitAndDoesNotImplyOtherRights() throws Exception {
        var access = PeerPermissionPolicy.parse("addr@127.0.0.1/32").accessFor(InetAddress.getLoopbackAddress());
        assertEquals(Set.of(PeerPermission.ADDR), access.permissions());
        assertFalse(access.manual());
    }
    @Test void numericRulesAreExplicitUnionedAndImmutable() throws Exception {
        var policy = PeerPermissionPolicy.parse("download@192.0.2.0/24;noban@192.0.2.8/32;noban@2001:db8::/32");
        var download = policy.accessFor(InetAddress.getByName("192.0.2.7"));
        assertEquals(Set.of(PeerPermission.DOWNLOAD), download.permissions());
        var protectedPeer = policy.accessFor(InetAddress.getByName("192.0.2.8"));
        assertEquals(Set.of(PeerPermission.DOWNLOAD, PeerPermission.NOBAN), protectedPeer.permissions());
        assertFalse(protectedPeer.manual());
        assertThrows(UnsupportedOperationException.class, () -> protectedPeer.permissions().clear());
        assertTrue(policy.accessFor(InetAddress.getByName("2001:db8::1")).permissions().contains(PeerPermission.NOBAN));
        assertTrue(policy.accessFor(InetAddress.getByName("198.51.100.1")).permissions().isEmpty());
    }
    @Test void hostnamesImplicitRulesAndUnsupportedPermissionsAreRejected() {
        for (String rule : new String[]{"noban@localhost", "127.0.0.1", "noban@", "@127.0.0.1", "all@127.0.0.1",
                "noban,@127.0.0.1", "noban@127.0.0.1/33", "noban@127.0.0.1;", "noban@deadbeef"}) {
            assertThrows(IllegalArgumentException.class, () -> PeerPermissionPolicy.parse(rule), rule);
        }
    }
    @Test void manualAndNobanPrivilegesAreDistinctAndDefaultIsUnprivileged() {
        assertTrue(PeerAccess.ordinary().permissions().isEmpty());
        assertFalse(PeerAccess.ordinary().manual());
        assertTrue(PeerAccess.manualConnection().manual());
        assertTrue(PeerAccess.manualConnection().permissions().isEmpty());
        assertEquals(Set.of(PeerPermission.NOBAN, PeerPermission.DOWNLOAD), new PeerAccess(Set.of(PeerPermission.NOBAN), false).permissions());
    }
}
