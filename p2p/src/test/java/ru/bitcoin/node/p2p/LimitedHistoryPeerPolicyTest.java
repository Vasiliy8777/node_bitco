package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;

import static org.junit.jupiter.api.Assertions.*;

class LimitedHistoryPeerPolicyTest {

    @Test
    void fullHistoryPeerIsAlwaysUsableForCurrentSyncPosition() {
        VersionMessage version = version(
                VersionMessage.NODE_NETWORK | VersionMessage.NODE_WITNESS,
                1
        );
        assertTrue(LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(version, 1_000_000));
    }

    @Test
    void limitedPeerAtAdmissionBoundaryIsUsable() {
        VersionMessage version = version(
                VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS,
                856
        );
        assertTrue(LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(version, 1_000));
    }

    @Test
    void limitedPeerOneBlockBeyondAdmissionBoundaryIsRejected() {
        VersionMessage version = version(
                VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS,
                855
        );
        assertFalse(LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(version, 1_000));
    }

    @Test
    void limitedPeerAheadOfLocalTipIsUsable() {
        VersionMessage version = version(
                VersionMessage.NODE_NETWORK_LIMITED | VersionMessage.NODE_WITNESS,
                1_050
        );
        assertTrue(LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(version, 1_000));
    }

    @Test
    void serviceWithoutNetworkHistoryIsRejected() {
        VersionMessage version = version(VersionMessage.NODE_WITNESS, 1_000);
        assertFalse(LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(version, 1_000));
    }

    @Test
    void constantsMatchBip159CorePolicy() {
        assertEquals(288, LimitedHistoryPeerPolicy.MIN_BLOCKS_TO_SERVE);
        assertEquals(144, LimitedHistoryPeerPolicy.ALLOW_CONNECTION_BLOCKS);
    }

    private static VersionMessage version(long services, int startHeight) {
        return new VersionMessage(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                services,
                1_700_000_000L,
                NetworkAddress.unspecified(),
                NetworkAddress.unspecified(),
                1L,
                "/limited-history-policy-test/",
                startHeight,
                true
        );
    }
}
