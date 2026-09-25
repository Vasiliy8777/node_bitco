package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.VersionMessage;

import java.util.Objects;

/**
 * BIP159 policy for deciding whether an outbound peer that only advertises
 * NODE_NETWORK_LIMITED is safe to use for the node's current sync position.
 *
 * <p>Bitcoin Core requires NODE_NETWORK_LIMITED peers to retain at least 288
 * recent blocks and only considers such peers desirable when they are no more
 * than 144 blocks behind the local active tip.  The 144-block admission window
 * leaves another 144 blocks of advertised history as reorg/download margin.
 */
public final class LimitedHistoryPeerPolicy {

    public static final int MIN_BLOCKS_TO_SERVE = 288;
    public static final int ALLOW_CONNECTION_BLOCKS = 144;

    private LimitedHistoryPeerPolicy() {
    }

    public static boolean hasFullHistory(long services) {
        return (services & VersionMessage.NODE_NETWORK) != 0;
    }

    public static boolean hasLimitedHistory(long services) {
        return (services & VersionMessage.NODE_NETWORK_LIMITED) != 0;
    }


    /**
     * Returns whether the peer's advertised services make it eligible for a
     * request at the supplied validated block height.
     *
     * <p>A NODE_NETWORK peer is treated as full-history. A peer that advertises
     * NODE_NETWORK_LIMITED without NODE_NETWORK is only used for the most recent
     * 288 blocks relative to the height it announced in VERSION.
     */
    public static boolean canServeBlockHeight(
            VersionMessage remoteVersion,
            long blockHeight
    ) {
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        if (blockHeight < 0) {
            throw new IllegalArgumentException("blockHeight must not be negative");
        }

        long services = remoteVersion.services();
        if (hasFullHistory(services)) {
            return true;
        }
        if (!hasLimitedHistory(services)) {
            return false;
        }

        long oldestAdvertisedHeight = Math.max(
                0L,
                (long) remoteVersion.startHeight() - MIN_BLOCKS_TO_SERVE + 1L
        );
        return blockHeight >= oldestAdvertisedHeight;
    }

    public static boolean canServeCurrentSyncPosition(
            VersionMessage remoteVersion,
            int localActiveHeight
    ) {
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        if (localActiveHeight < 0) {
            throw new IllegalArgumentException("localActiveHeight must not be negative");
        }

        long services = remoteVersion.services();
        if (hasFullHistory(services)) {
            return true;
        }
        if (!hasLimitedHistory(services)) {
            return false;
        }

        long minimumUsefulHeight = Math.max(
                0L,
                (long) localActiveHeight - ALLOW_CONNECTION_BLOCKS
        );
        return remoteVersion.startHeight() >= minimumUsefulHeight;
    }
}
