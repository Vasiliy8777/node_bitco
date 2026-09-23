package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.OutboundPeerSelector;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.address.PeerNetGroup;
import ru.bitcoin.node.p2p.address.TriedCollision;
import ru.bitcoin.node.p2p.message.VersionMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class OutboundPeerManager {

    private final PeerConnector peerConnector;
    private final PeerManager peerManager;
    private final PeerAddressManager addressManager;
    private final OutboundPeerSelector selector;
    private final Supplier<Instant> clock;

    public OutboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager addressManager
    ) {
        this(
                bitcoinClient,
                peerManager,
                addressManager,
                new OutboundPeerSelector(
                        addressManager
                ),
                Instant::now
        );
    }

    OutboundPeerManager(
            PeerConnector peerConnector,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            OutboundPeerSelector selector,
            Supplier<Instant> clock
    ) {

        this.peerConnector =
                Objects.requireNonNull(
                        peerConnector,
                        "peerConnector"
                );

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );

        this.selector =
                Objects.requireNonNull(
                        selector,
                        "selector"
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock"
                );
    }

    public Peer connectOne(
            int startHeight
    ) throws IOException {

        return connectOneWithAddress(
                startHeight,
                List.of()
        ).peer();
    }

    public Peer connectOne(
            int startHeight,
            List<PeerAddress> excludedAddresses
    ) throws IOException {

        return connectOneWithAddress(
                startHeight,
                excludedAddresses
        ).peer();
    }

    public OutboundPeerConnection connectOneWithAddress(
            int startHeight,
            List<PeerAddress> excludedAddresses
    ) throws IOException {
        return connectOneWithAddress(startHeight, excludedAddresses, Set.of());
    }

    public OutboundPeerConnection connectOneWithAddress(
            int startHeight,
            List<PeerAddress> excludedAddresses,
            Set<PeerNetGroup> excludedNetGroups
    ) throws IOException {

        if (startHeight < 0) {

            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        Objects.requireNonNull(
                excludedAddresses,
                "excludedAddresses"
        );
        Objects.requireNonNull(
                excludedNetGroups,
                "excludedNetGroups"
        );

        Set<PeerAddress> attempted =
                new java.util.LinkedHashSet<>(
                        excludedAddresses
                );

        IOException failure =
                new IOException(
                        "Unable to connect to any known peer address"
                );

        while (true) {

            PeerAddress address =
                    selector.select(
                                    attempted,
                                    excludedNetGroups
                            )
                            .orElse(
                                    null
                            );

            if (address == null) {

                if (attempted.size()
                        == excludedAddresses.size()) {

                    throw new IOException(
                            "No known peer addresses available"
                    );
                }

                throw failure;
            }

            attempted.add(
                    address
            );

            addressManager.markAttempt(
                    address,
                    now()
            );

            try {

                Peer peer =
                        peerConnector.connectManaged(
                                address.hostAddress(),
                                address.port(),
                                startHeight
                        );

                if (!peer.isReady()) {
                    rejectPeer(
                            peer,
                            failure,
                            "BitcoinClient returned non-ready peer "
                                    + address.hostAddress()
                                    + ":"
                                    + address.port()
                    );
                    continue;
                }

                String ineligibleReason =
                        longLivedOutboundIneligibilityReason(
                                peer.remoteVersion()
                        );

                if (ineligibleReason != null) {
                    rejectPeer(
                            peer,
                            failure,
                            "Ineligible long-lived outbound peer "
                                    + address.hostAddress()
                                    + ":"
                                    + address.port()
                                    + ": "
                                    + ineligibleReason
                    );
                    continue;
                }

                addressManager.markSuccess(
                        address,
                        now()
                );

                try {

                    peerManager.add(
                            peer
                    );

                } catch (RuntimeException exception) {

                    try {

                        peer.close();

                    } catch (IOException closeException) {

                        exception.addSuppressed(
                                closeException
                        );
                    }

                    throw exception;
                }

                return new OutboundPeerConnection(
                        peer,
                        address
                );

            } catch (IOException exception) {

                failure.addSuppressed(
                        exception
                );
            }
        }
    }

    /**
     * Performs one test-before-evict attempt for a pending TRIED collision.
     *
     * <p>The feeler is intentionally short-lived:
     * it is never registered in PeerManager and therefore never consumes
     * a persistent outbound slot.
     *
     * @return true when a pending collision was processed; false when
     *         there was no pending TRIED collision.
     */
    public boolean tryTriedCollisionFeeler(
            int startHeight
    ) throws IOException {

        if (startHeight < 0) {
            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        /*
         * Resolve collisions whose previous feeler result or timeout
         * already gives AddrMan enough information to make a decision.
         */
        addressManager.resolveTriedCollisions(
                now()
        );

        TriedCollision collision =
                addressManager
                        .selectTriedCollision()
                        .orElse(
                                null
                        );

        if (collision == null) {
            return false;
        }

        PeerAddress incumbent =
                collision.incumbent();

        /*
         * Bitcoin Core does not create another connection if the
         * incumbent is already connected. The existing connection
         * itself proves that the address is alive.
         */
        if (isAlreadyConnected(
                incumbent
        )) {

            addressManager.markSuccess(
                    incumbent,
                    now()
            );

            addressManager.resolveTriedCollisions(
                    now()
            );

            return true;
        }

        addressManager.markAttempt(
                incumbent,
                now()
        );

        Peer feeler = null;

        try {

            feeler =
                    peerConnector.connect(
                            incumbent.hostAddress(),
                            incumbent.port(),
                            startHeight
                    );

            if (!feeler.isReady()) {

                throw new IOException(
                        "Feeler connection did not complete handshake with "
                                + incumbent.hostAddress()
                                + ":"
                                + incumbent.port()
                );
            }

            /*
             * A successful handshake proves that the incumbent is alive.
             *
             * markSuccess() updates both lastSuccess and lastAttempt,
             * which causes resolveTriedCollisions() to preserve the
             * incumbent.
             */
            addressManager.markSuccess(
                    incumbent,
                    now()
            );

            addressManager.resolveTriedCollisions(
                    now()
            );

            return true;

        } finally {

            /*
             * FEELER is deliberately not added to PeerManager.
             * Close it immediately after the liveness check.
             */
            if (feeler != null) {

                try {
                    feeler.close();
                } catch (IOException ignored) {
                    // Best-effort feeler cleanup.
                }
            }
        }
    }

    private boolean isAlreadyConnected(
            PeerAddress address
    ) {

        for (Peer peer :
                peerManager.readyPeers()) {

            InetSocketAddress remote =
                    peer.remoteAddress();

            if (remote == null
                    || remote.getAddress() == null) {

                continue;
            }

            if (remote.getPort()
                    != address.port()) {

                continue;
            }

            if (remote.getAddress()
                    .equals(
                            address.address()
                    )) {

                return true;
            }
        }

        return false;
    }

    private static String longLivedOutboundIneligibilityReason(
            VersionMessage version
    ) {
        if (version.version()
                < VersionMessage.MIN_PEER_PROTOCOL_VERSION) {
            return "protocol version "
                    + version.version()
                    + " is below minimum "
                    + VersionMessage.MIN_PEER_PROTOCOL_VERSION;
        }

        long services =
                version.services();

        if ((services & VersionMessage.NODE_WITNESS) == 0) {
            return "NODE_WITNESS is required";
        }

        /*
         * This manager currently owns full-relay outbound slots.
         * NODE_NETWORK_LIMITED is intentionally not treated as a substitute
         * here: such peers cannot serve arbitrary historical blocks and need
         * a separate post-IBD/limited-history connection policy.
         */
        if ((services & VersionMessage.NODE_NETWORK) == 0) {
            return "NODE_NETWORK is required for a full-relay outbound slot";
        }

        return null;
    }

    private static void rejectPeer(
            Peer peer,
            IOException aggregateFailure,
            String reason
    ) {
        IOException rejection =
                new IOException(
                        reason
                );

        try {
            peer.close();
        } catch (IOException closeException) {
            rejection.addSuppressed(
                    closeException
            );
        }

        aggregateFailure.addSuppressed(
                rejection
        );
    }

    private Instant now() {

        return Objects.requireNonNull(
                clock.get(),
                "clock returned null"
        );
    }
}
