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
import java.util.ArrayList;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

public final class OutboundPeerManager {

    private static final System.Logger log =
            System.getLogger(OutboundPeerManager.class.getName());

    private final PeerConnector peerConnector;
    private final PeerManager peerManager;
    private final PeerAddressManager addressManager;
    private final OutboundPeerSelector selector;
    private final Supplier<Instant> clock;
    private final PeerDiscouragementManager discouragementManager;
    private final BooleanSupplier initialBlockDownload;
    private volatile boolean connectionAttemptsCancelled;

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
                Instant::now,
                peerManager.discouragementManager(),
                () -> false
        );
    }

    public OutboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            BooleanSupplier initialBlockDownload
    ) {
        this(
                bitcoinClient,
                peerManager,
                addressManager,
                new OutboundPeerSelector(addressManager),
                Instant::now,
                peerManager.discouragementManager(),
                initialBlockDownload
        );
    }

    OutboundPeerManager(
            PeerConnector peerConnector,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            OutboundPeerSelector selector,
            Supplier<Instant> clock
    ) {
        this(peerConnector, peerManager, addressManager, selector, clock,
                peerManager.discouragementManager(), () -> false);
    }

    OutboundPeerManager(
            PeerConnector peerConnector,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            OutboundPeerSelector selector,
            Supplier<Instant> clock,
            PeerDiscouragementManager discouragementManager
    ) {
        this(peerConnector, peerManager, addressManager, selector, clock,
                discouragementManager, () -> false);
    }

    OutboundPeerManager(
            PeerConnector peerConnector,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            OutboundPeerSelector selector,
            Supplier<Instant> clock,
            PeerDiscouragementManager discouragementManager,
            BooleanSupplier initialBlockDownload
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

        this.discouragementManager = Objects.requireNonNull(
                discouragementManager, "discouragementManager");
        this.initialBlockDownload = Objects.requireNonNull(
                initialBlockDownload, "initialBlockDownload");
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


    /**
     * Header-IBD connection race. Candidate addresses are reserved serially through the
     * normal AddrMan selector, then connect/handshake runs concurrently. The first fully
     * READY eligible peer wins; all later successful racers are immediately removed/closed.
     */
    public OutboundPeerConnection connectHeaderPeerRace(
            int startHeight,
            List<PeerAddress> excludedAddresses,
            int width
    ) throws IOException {
        if (width < 1) throw new IllegalArgumentException("width must be positive");
        Objects.requireNonNull(excludedAddresses, "excludedAddresses");

        Set<PeerAddress> reserved = new java.util.LinkedHashSet<>(excludedAddresses);
        Set<PeerNetGroup> groups = new java.util.LinkedHashSet<>();
        List<PeerAddress> candidates = new ArrayList<>(width);

        while (candidates.size() < width) {
            PeerAddress candidate = selector.select(reserved, groups).orElse(null);
            if (candidate == null) {
                // Diversity is preferred, not mandatory: fill remaining lanes from other addresses.
                candidate = selector.select(reserved, Set.of()).orElse(null);
            }
            if (candidate == null) break;
            reserved.add(candidate);
            if (containsEndpoint(excludedAddresses, candidate)) continue;
            if (candidate.isDirectSocketAddress()
                    && (discouragementManager.isDiscouraged(candidate.address())
                    || peerManager.banManager().isBanned(candidate.address()))) continue;
            candidates.add(candidate);
            if (PeerNetGroup.isDiversifiable(candidate)) groups.add(PeerNetGroup.of(candidate));
        }

        if (candidates.isEmpty()) {
            return connectOneWithAddress(startHeight, excludedAddresses);
        }

        log.log(System.Logger.Level.INFO,
                "HEADER PEER RACE starting lanes={0} candidates={1}",
                candidates.size(), candidates);

        ExecutorService executor = Executors.newFixedThreadPool(candidates.size(), task -> {
            Thread thread = new Thread(task, "bitcoin-header-peer-race");
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<OutboundPeerConnection> completion = new ExecutorCompletionService<>(executor);
        AtomicBoolean winnerClaimed = new AtomicBoolean();
        List<Future<OutboundPeerConnection>> futures = new ArrayList<>();
        IOException aggregate = new IOException("No header peer race candidate completed handshake");

        try {
            for (PeerAddress candidate : candidates) {
                addressManager.markAttempt(candidate, now());
                futures.add(completion.submit(() -> {
                    OutboundPeerConnection connection = connectReservedCandidate(startHeight, candidate);
                    if (winnerClaimed.compareAndSet(false, true)) return connection;
                    peerManager.remove(connection.peer());
                    try { connection.peer().close(); } catch (IOException ignored) { }
                    throw new IOException("Header peer race lost after successful handshake: " + candidate);
                }));
            }

            for (int i = 0; i < candidates.size(); i++) {
                try {
                    OutboundPeerConnection winner = completion.take().get();
                    log.log(System.Logger.Level.INFO,
                            "HEADER PEER RACE winner={0} advertisedHeight={1} transport={2}",
                            winner.address(), winner.peer().remoteVersion().startHeight(),
                            winner.peer().isV2Transport() ? "v2" : "v1");
                    return winner;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while racing header peers", interrupted);
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    aggregate.addSuppressed(cause instanceof Exception e ? e : new IOException(cause));
                }
            }
            throw aggregate;
        } finally {
            for (Future<?> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
    }

    private OutboundPeerConnection connectReservedCandidate(int startHeight, PeerAddress address) throws IOException {
        return connectReservedPersistentCandidate(
                startHeight,
                address,
                PeerConnectionRole.FULL_RELAY
        );
    }

    /**
     * Selects one address for a persistent outbound slot without opening a socket.
     * The supervisor serializes only this short reservation step and then performs
     * the expensive network handshake concurrently in the slot worker.
     */
    PeerAddress selectPersistentCandidate(
            List<PeerAddress> excludedAddresses,
            Set<PeerNetGroup> excludedNetGroups
    ) throws IOException {
        Objects.requireNonNull(excludedAddresses, "excludedAddresses");
        Objects.requireNonNull(excludedNetGroups, "excludedNetGroups");

        Set<PeerAddress> attempted = new java.util.LinkedHashSet<>(excludedAddresses);

        while (true) {
            if (connectionAttemptsCancelled || Thread.currentThread().isInterrupted()) {
                throw new IOException("Outbound peer connection cancelled");
            }

            PeerAddress address = selector.select(attempted, excludedNetGroups).orElse(null);
            if (address == null && !excludedNetGroups.isEmpty()) {
                // Netgroup diversity is preferred, not a hard availability filter.
                address = selector.select(attempted, Set.of()).orElse(null);
            }
            if (address == null) {
                throw new IOException("No known peer addresses available for outbound reservation");
            }

            attempted.add(address);
            if (address.isDirectSocketAddress()
                    && (discouragementManager.isDiscouraged(address.address())
                    || peerManager.banManager().isBanned(address.address()))) {
                continue;
            }
            return address;
        }
    }

    /** Connects exactly the address already reserved by OutboundPeerSupervisor. */
    OutboundPeerConnection connectReservedPersistentCandidate(
            int startHeight,
            PeerAddress address,
            PeerConnectionRole role
    ) throws IOException {
        if (startHeight < 0) {
            throw new IllegalArgumentException("startHeight must not be negative");
        }
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(role, "role");
        if (!role.persistentOutbound()) {
            throw new IllegalArgumentException("role must be a persistent outbound role");
        }
        if (connectionAttemptsCancelled || Thread.currentThread().isInterrupted()) {
            throw new IOException("Outbound peer connection cancelled");
        }

        addressManager.markAttempt(address, now());
        long startedNanos = System.nanoTime();
        log.log(System.Logger.Level.INFO,
                "Connecting reserved outbound peer candidate {0}:{1}, role={2}",
                address.hostAddress(), address.port(), role);

        Peer peer = null;
        try {
            peer = peerConnector.connectManaged(
                    address.hostAddress(), address.port(), startHeight, role);

            if (connectionAttemptsCancelled || Thread.currentThread().isInterrupted()) {
                try { peer.close(); } catch (IOException ignored) { }
                throw new IOException("Outbound peer connection cancelled");
            }
            if (!peer.isReady()) {
                IOException failure = new IOException("BitcoinClient returned non-ready peer " + address);
                rejectPeer(peer, failure, failure.getMessage());
                throw failure;
            }

            String ineligible = longLivedOutboundIneligibilityReason(
                    peer.remoteVersion(), startHeight, initialBlockDownload.getAsBoolean());
            if (ineligible != null) {
                IOException failure = new IOException(
                        "Ineligible long-lived outbound peer " + address + ": " + ineligible);
                rejectPeer(peer, failure, failure.getMessage());
                throw failure;
            }

            addressManager.markSuccess(address, now());
            try {
                peerManager.add(peer, role);
            } catch (RuntimeException failure) {
                try { peer.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }

            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos);
            log.log(System.Logger.Level.INFO,
                    "Outbound peer READY {0}:{1}, role={2}, transport={3}, advertisedHeight={4}, handshakeMs={5}",
                    address.hostAddress(), address.port(), role,
                    peer.isV2Transport() ? "v2" : "v1",
                    peer.remoteVersion().startHeight(), elapsedMillis);

            return new OutboundPeerConnection(peer, address, role);
        } catch (IOException | RuntimeException failure) {
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos);
            log.log(System.Logger.Level.WARNING,
                    "Outbound peer candidate failed {0}:{1}, role={2}, elapsedMs={3}: {4}",
                    address.hostAddress(), address.port(), role, elapsedMillis, failure.toString());
            throw failure;
        }
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
        return connectOneWithAddress(startHeight, excludedAddresses, excludedNetGroups, PeerConnectionRole.FULL_RELAY);
    }

    public OutboundPeerConnection connectOneWithAddress(
            int startHeight,
            List<PeerAddress> excludedAddresses,
            Set<PeerNetGroup> excludedNetGroups,
            PeerConnectionRole role
    ) throws IOException {
        Objects.requireNonNull(role, "role");
        if (!role.persistentOutbound()) {
            throw new IllegalArgumentException("role must be a persistent outbound role");
        }

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

            if (connectionAttemptsCancelled || Thread.currentThread().isInterrupted()) {
                throw new IOException("Outbound peer connection cancelled");
            }

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

            if (address.isDirectSocketAddress()
                    && (discouragementManager.isDiscouraged(address.address())
                    || peerManager.banManager().isBanned(address.address()))) {
                continue;
            }

            addressManager.markAttempt(
                    address,
                    now()
            );

            log.log(
                    System.Logger.Level.INFO,
                    "Connecting outbound peer candidate {0}:{1} (attempted={2})",
                    address.hostAddress(),
                    address.port(),
                    attempted.size()
            );

            try {

                Peer peer =
                        peerConnector.connectManaged(
                                address.hostAddress(),
                                address.port(),
                                startHeight,
                                role
                        );

                if (connectionAttemptsCancelled || Thread.currentThread().isInterrupted()) {
                    try {
                        peer.close();
                    } catch (IOException ignored) {
                        // Cancellation is the primary failure.
                    }
                    throw new IOException("Outbound peer connection cancelled");
                }

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
                                peer.remoteVersion(),
                                startHeight,
                                initialBlockDownload.getAsBoolean()
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
                            peer,
                            role
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
                        address,
                        role
                );

            } catch (IOException exception) {

                failure.addSuppressed(
                        exception
                );

                log.log(
                        System.Logger.Level.DEBUG,
                        "Outbound peer candidate {0}:{1} failed: {2}",
                        address.hostAddress(),
                        address.port(),
                        exception.toString()
                );
            }
        }
    }

    /**
     * Permanently cancels connection establishment for this manager.
     * Node shutdown is terminal, so no new outbound attempt may begin afterwards.
     */
    public void cancelPendingConnections() {
        connectionAttemptsCancelled = true;
        peerConnector.cancelPendingConnections();
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
            VersionMessage version,
            int localActiveHeight,
            boolean initialBlockDownload
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

        if (LimitedHistoryPeerPolicy.hasFullHistory(services)) {
            return null;
        }

        if (!LimitedHistoryPeerPolicy.hasLimitedHistory(services)) {
            return "NODE_NETWORK or NODE_NETWORK_LIMITED is required for a persistent outbound slot";
        }

        if (initialBlockDownload) {
            return "NODE_NETWORK_LIMITED peer is not sufficient during initial block download";
        }

        if (!LimitedHistoryPeerPolicy.canServeCurrentSyncPosition(
                version,
                localActiveHeight
        )) {
            return "NODE_NETWORK_LIMITED peer is too far behind local active height: remote="
                    + version.startHeight()
                    + ", local="
                    + localActiveHeight
                    + ", maximum lag="
                    + LimitedHistoryPeerPolicy.ALLOW_CONNECTION_BLOCKS;
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

    private static boolean containsEndpoint(
            List<PeerAddress> excludedAddresses,
            PeerAddress candidate
    ) {
        for (PeerAddress excluded : excludedAddresses) {
            if (excluded.port() == candidate.port()
                    && excluded.hostAddress().equalsIgnoreCase(candidate.hostAddress())) {
                return true;
            }
        }
        return false;
    }
}
