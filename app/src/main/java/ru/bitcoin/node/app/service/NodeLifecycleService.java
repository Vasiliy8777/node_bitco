package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.HeaderSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.OutboundPeerConnection;
import ru.bitcoin.node.p2p.OutboundPeerManager;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NodeLifecycleService
        implements NodeLifecycle {

    private static final Logger log =
            LoggerFactory.getLogger(
                    NodeLifecycleService.class
            );

    private static final Hash256 HEADER_SYNC_STOP_HASH =
            new Hash256(
                    new byte[Hash256.LENGTH]
            );

    private final NodeValidationService validationService;
    private final NodeSyncInfrastructure syncInfrastructure;

    private final PeerAddressManager addressManager;
    private final PeerDiscovery peerDiscovery;
    private final OutboundPeerManager outboundPeerManager;
    private final PeerManager peerManager;

    private final BlockSyncCoordinator blockSyncCoordinator;

    private final Duration headerResponseTimeout;

    private NodeLifecycleState state =
            NodeLifecycleState.NEW;

    private Throwable failure;

    public NodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerDiscovery peerDiscovery,
            OutboundPeerManager outboundPeerManager,
            PeerManager peerManager,
            BlockSyncCoordinator blockSyncCoordinator,
            Duration headerResponseTimeout
    ) {

        this.validationService =
                Objects.requireNonNull(
                        validationService,
                        "validationService"
                );

        this.syncInfrastructure =
                Objects.requireNonNull(
                        syncInfrastructure,
                        "syncInfrastructure"
                );

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );

        this.peerDiscovery =
                Objects.requireNonNull(
                        peerDiscovery,
                        "peerDiscovery"
                );

        this.outboundPeerManager =
                Objects.requireNonNull(
                        outboundPeerManager,
                        "outboundPeerManager"
                );

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.blockSyncCoordinator =
                Objects.requireNonNull(
                        blockSyncCoordinator,
                        "blockSyncCoordinator"
                );

        this.headerResponseTimeout =
                Objects.requireNonNull(
                        headerResponseTimeout,
                        "headerResponseTimeout"
                );

        if (headerResponseTimeout.isZero()
                || headerResponseTimeout.isNegative()) {

            throw new IllegalArgumentException(
                    "headerResponseTimeout must be positive"
            );
        }
    }

    @Override
    public void start()
            throws IOException {

        synchronized (this) {

            if (state != NodeLifecycleState.NEW) {

                throw new IllegalStateException(
                        "Node cannot start from state "
                                + state
                );
            }

            failure =
                    null;
        }

        setState(
                NodeLifecycleState.STARTING
        );

        try {

            bootstrapAddresses();

            ensureNotStopping();

            long activeHeight =
                    validationService
                            .activeTip()
                            .height();

            int startHeight =
                    Math.toIntExact(
                            activeHeight
                    );

            synchronizeHeadersWithFailover(
                    startHeight
            );

            ensureNotStopping();

            synchronizeBlocks();

            ensureNotStopping();

            synchronized (this) {

                verifySynchronized();

                if (state == NodeLifecycleState.STOPPING
                        || state == NodeLifecycleState.STOPPED) {

                    return;
                }
            }

            setState(
                    NodeLifecycleState.RUNNING
            );

        } catch (IOException | RuntimeException exception) {

            if (isStoppingOrStopped()) {
                return;
            }

            fail(
                    exception
            );

            throw exception;
        }
    }

    private synchronized boolean isStoppingOrStopped() {

        return state == NodeLifecycleState.STOPPING
                || state == NodeLifecycleState.STOPPED;
    }

    private void ensureNotStopping()
            throws IOException {

        synchronized (this) {

            if (state == NodeLifecycleState.STOPPING
                    || state == NodeLifecycleState.STOPPED) {

                throw new IOException(
                        "Node startup cancelled"
                );
            }
        }
    }

    private void bootstrapAddresses() {

        if (!addressManager.isEmpty()) {
            return;
        }

        peerDiscovery.discover();
    }

    private void synchronizeHeaders(
            Peer peer
    ) throws IOException {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        setState(
                NodeLifecycleState.SYNCHRONIZING_HEADERS
        );

        HeaderSynchronizer headerSynchronizer =
                new HeaderSynchronizer(
                        peer,
                        headerResponseTimeout
                );

        HeaderSyncCoordinator coordinator =
                new HeaderSyncCoordinator(
                        headerSynchronizer,
                        syncInfrastructure.headerSyncService(),
                        syncInfrastructure.headerChainState(),
                        syncInfrastructure.blockLocatorBuilder()
                );

        coordinator.synchronize(
                HEADER_SYNC_STOP_HASH
        );
    }

    private void synchronizeHeadersWithFailover(
            int startHeight
    ) throws IOException {

        List<PeerAddress> failedAddresses =
                new ArrayList<>();

        IOException failure =
                new IOException(
                        "Unable to synchronize headers "
                                + "with any known peer"
                );

        while (true) {

            OutboundPeerConnection connection;

            try {

                connection =
                        outboundPeerManager
                                .connectOneWithAddress(
                                        startHeight,
                                        failedAddresses
                                );

            } catch (IOException connectFailure) {

                failure.addSuppressed(
                        connectFailure
                );

                throw failure;
            }

            Peer peer =
                    connection.peer();

            try {

                synchronizeHeaders(
                        peer
                );

                return;

            } catch (IOException syncFailure) {

                if (isStoppingOrStopped()) {
                    throw syncFailure;
                }

                failure.addSuppressed(
                        syncFailure
                );

                failedAddresses.add(
                        connection.address()
                );

                peerManager.remove(
                        peer
                );

                try {

                    peer.close();

                } catch (IOException closeFailure) {

                    syncFailure.addSuppressed(
                            closeFailure
                    );
                }
            }
        }
    }

    private void synchronizeBlocks()
            throws IOException {

        setState(
                NodeLifecycleState.SYNCHRONIZING_BLOCKS
        );

        blockSyncCoordinator.synchronize();
    }

    private void verifySynchronized() {

        BlockIndex activeTip =
                validationService.activeTip();

        BlockIndex bestHeaderTip =
                syncInfrastructure
                        .headerChainState()
                        .bestHeaderTip();

        if (!activeTip.hash().equals(
                bestHeaderTip.hash()
        )) {

            throw new IllegalStateException(
                    "Initial block synchronization ended "
                            + "before active tip reached best header tip: "
                            + "activeHeight="
                            + activeTip.height()
                            + ", bestHeaderHeight="
                            + bestHeaderTip.height()
            );
        }
    }

    private void setState(
            NodeLifecycleState newState
    ) {

        Objects.requireNonNull(
                newState,
                "newState"
        );

        NodeLifecycleState previousState;

        synchronized (this) {

            if (state == NodeLifecycleState.STOPPING
                    || state == NodeLifecycleState.STOPPED
                    || state == NodeLifecycleState.FAILED) {

                throw new IllegalStateException(
                        "Cannot transition node from "
                                + state
                                + " to "
                                + newState
                );
            }

            previousState =
                    state;

            state =
                    newState;
        }

        log.info(
                "Bitcoin node state: {} -> {}",
                previousState,
                newState
        );
    }

    private void fail(
            Throwable exception
    ) {

        Objects.requireNonNull(
                exception,
                "exception"
        );

        NodeLifecycleState previousState;

        synchronized (this) {

            previousState =
                    state;

            failure =
                    exception;

            state =
                    NodeLifecycleState.FAILED;
        }

        log.error(
                "Bitcoin node state: {} -> FAILED",
                previousState,
                exception
        );

        try {

            peerManager.close();

        } catch (IOException closeException) {

            exception.addSuppressed(
                    closeException
            );
        }
    }

    public synchronized NodeLifecycleState state() {
        return state;
    }

    public synchronized Optional<Throwable> failure() {

        return Optional.ofNullable(
                failure
        );
    }

    public synchronized boolean isRunning() {

        return state == NodeLifecycleState.RUNNING;
    }

    @Override
    public void close()
            throws IOException {

        NodeLifecycleState previousState;

        synchronized (this) {

            if (state == NodeLifecycleState.STOPPED) {
                return;
            }

            if (state == NodeLifecycleState.STOPPING) {
                return;
            }

            previousState =
                    state;

            state =
                    NodeLifecycleState.STOPPING;
        }

        log.info(
                "Bitcoin node state: {} -> STOPPING",
                previousState
        );

        IOException closeFailure =
                null;

        try {

            peerManager.close();

        } catch (IOException exception) {

            closeFailure =
                    exception;
        }

        if (closeFailure == null) {

            synchronized (this) {

                state =
                        NodeLifecycleState.STOPPED;
            }

            log.info(
                    "Bitcoin node state: STOPPING -> STOPPED"
            );

        } else {

            synchronized (this) {

                failure =
                        closeFailure;

                state =
                        NodeLifecycleState.FAILED;
            }

            log.error(
                    "Bitcoin node state: STOPPING -> FAILED",
                    closeFailure
            );

            throw closeFailure;
        }
    }
}