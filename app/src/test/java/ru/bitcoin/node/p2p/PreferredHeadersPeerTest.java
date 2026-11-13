package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreferredHeadersPeerTest {
    @Test void versionOnlyAlternativeAffectsTimeoutWithoutBecomingDownloadSource() throws Exception {
        try (var peers = new PeerManager()) {
            var source = peer(false, false, VersionMessage.NODE_NETWORK);
            peers.add(source);
            peers.reserveHeadersSync(source, 0, 1_000_000, 0, 600, false);
            var alternative = peer(false, false, VersionMessage.NODE_NETWORK_LIMITED);
            when(alternative.isReady()).thenReturn(false);
            when(alternative.state()).thenReturn(PeerState.VERACK_SENT);
            peers.trackHandshake(alternative, PeerConnectionRole.FULL_RELAY);
            assertEquals(1, peers.preferredDownloadPeerCount()); // no VERSION yet
            var alternativeVersion = alternative.remoteVersion();
            when(alternative.evictionVersion()).thenReturn(alternativeVersion);
            assertEquals(2, peers.preferredDownloadPeerCount());
            assertEquals(java.util.List.of(source), peers.readyPeers());
            assertFalse(peers.canSyncFrom(alternative, false));
            assertFalse(peers.reserveHeadersSync(alternative, 0, 1_000_000, 0, 600, false).allowed());
            assertEquals(ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.Action.DISCONNECT,
                    peers.headersSyncTimeout(source, Long.MAX_VALUE - 1, 1_000_000, 0));
            when(alternative.isReady()).thenReturn(true);
            when(alternative.state()).thenReturn(PeerState.READY);
            peers.add(alternative);
            assertEquals(2, peers.preferredDownloadPeerCount()); // atomic promotion, no double count
            peers.remove(alternative);
            assertEquals(1, peers.preferredDownloadPeerCount());
        }
    }

    @Test void handshakeAccountingRespectsPermissionsRolesServicesAndClosure() throws Exception {
        try (var peers = new PeerManager()) {
            var inbound = peer(true, false, VersionMessage.NODE_NETWORK);
            var noBan = peer(true, true, VersionMessage.NODE_NETWORK_LIMITED);
            var fetch = peer(false, true, VersionMessage.NODE_NETWORK);
            var client = peer(false, false, VersionMessage.NODE_WITNESS);
            for (var candidate : java.util.List.of(inbound, noBan, fetch, client)) {
                when(candidate.isReady()).thenReturn(false);
                when(candidate.state()).thenReturn(PeerState.VERACK_SENT);
                var version = candidate.remoteVersion();
                when(candidate.evictionVersion()).thenReturn(version);
                peers.trackHandshake(candidate, candidate == fetch ? PeerConnectionRole.ADDR_FETCH : PeerConnectionRole.INBOUND);
            }
            assertEquals(1, peers.preferredDownloadPeerCount());
            when(noBan.state()).thenReturn(PeerState.CLOSED);
            assertEquals(0, peers.preferredDownloadPeerCount());
            assertTrue(peers.readyPeers().isEmpty());
        }
    }
    @Test void preferredCountsTrackRolesServicesNobanRemovalAndSyncReservation() throws Exception {
        try (var peers = new PeerManager()) {
            var outbound = peer(false,false,VersionMessage.NODE_NETWORK);
            var inbound = peer(true,false,VersionMessage.NODE_NETWORK);
            var noban = peer(true,true,VersionMessage.NODE_NETWORK_LIMITED);
            var fetch = peer(false,true,VersionMessage.NODE_NETWORK);
            var client = peer(false,false,VersionMessage.NODE_WITNESS);
            peers.add(outbound); peers.add(inbound,PeerConnectionRole.INBOUND); peers.add(noban,PeerConnectionRole.INBOUND);
            peers.add(fetch,PeerConnectionRole.ADDR_FETCH); peers.add(client);
            assertEquals(2,peers.preferredDownloadPeerCount());
            var reservation = peers.reserveHeadersSync(outbound,0,1_000_000,0,600,false);
            assertTrue(reservation.allowed()); assertTrue(reservation.newlyStarted()); assertEquals(1,peers.headersSyncStartedCount());
            assertFalse(peers.reserveHeadersSync(noban,0,1_000_000,0,600,false).allowed());
            peers.rollbackHeadersSync(outbound,reservation); assertEquals(0,peers.headersSyncStartedCount());
            peers.reserveHeadersSync(noban,0,1_000_000,0,600,false);
            assertEquals(ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.Action.RESET_NOBAN,
                    peers.headersSyncTimeout(noban,Long.MAX_VALUE-1,1_000_000,0));
            assertEquals(0,peers.headersSyncStartedCount());
            peers.remove(outbound); assertEquals(1,peers.preferredDownloadPeerCount());
            peers.remove(noban); assertEquals(0,peers.preferredDownloadPeerCount()); assertEquals(0,peers.headersSyncStartedCount());
        }
    }
    private static Peer peer(boolean inbound,boolean noBan,long services) {
        var peer=mock(Peer.class); when(peer.isReady()).thenReturn(true); when(peer.state()).thenReturn(PeerState.READY);
        when(peer.isInboundConnection()).thenReturn(inbound); when(peer.hasPermission(PeerPermission.NOBAN)).thenReturn(noBan);
        var version=mock(VersionMessage.class); when(version.services()).thenReturn(services); when(peer.remoteVersion()).thenReturn(version);
        return peer;
    }
}
