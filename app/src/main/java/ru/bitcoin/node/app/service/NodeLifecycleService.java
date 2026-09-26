package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.HeaderSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;

import java.io.IOException;
import java.math.BigInteger;
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
    private final OutboundPeerSupervisor outboundPeerSupervisor;
    private final PeerManager peerManager;

    private final BitcoinServer bitcoinServer;

    private final boolean listen;
    private final int listenPort;

    private final BlockSyncCoordinator blockSyncCoordinator;

    private final Duration headerResponseTimeout;
    private final BigInteger minimumChainWork;

    private NodeLifecycleState state =
            NodeLifecycleState.NEW;

    private Throwable failure;
    private final ru.bitcoin.node.app.sync.LiveChainSynchronizer liveSync;

    public boolean isMiningReady() {
        return isRunning() && liveSync.isCurrent();
    }

    public NodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerDiscovery peerDiscovery,
            OutboundPeerManager outboundPeerManager,
            OutboundPeerSupervisor outboundPeerSupervisor,
            PeerManager peerManager,
            BitcoinServer bitcoinServer,
            BlockSyncCoordinator blockSyncCoordinator,
            Duration headerResponseTimeout,
            boolean listen,
            int listenPort
    ) {
        this(validationService, syncInfrastructure, addressManager, peerDiscovery, outboundPeerManager,
                outboundPeerSupervisor, peerManager, bitcoinServer, blockSyncCoordinator,
                headerResponseTimeout, listen, listenPort, BigInteger.ZERO);
    }

    public NodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerDiscovery peerDiscovery,
            OutboundPeerManager outboundPeerManager,
            OutboundPeerSupervisor outboundPeerSupervisor,
            PeerManager peerManager,
            BitcoinServer bitcoinServer,
            BlockSyncCoordinator blockSyncCoordinator,
            Duration headerResponseTimeout,
            boolean listen,
            int listenPort,
            BigInteger minimumChainWork
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

        this.outboundPeerSupervisor =
                Objects.requireNonNull(
                        outboundPeerSupervisor,
                        "outboundPeerSupervisor"
                );

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.bitcoinServer =
                Objects.requireNonNull(
                        bitcoinServer,
                        "bitcoinServer"
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

        this.minimumChainWork = Objects.requireNonNull(minimumChainWork, "minimumChainWork");
        if (minimumChainWork.signum() < 0) {
            throw new IllegalArgumentException("minimumChainWork must not be negative");
        }

        if (headerResponseTimeout.isZero()
                || headerResponseTimeout.isNegative()) {

            throw new IllegalArgumentException(
                    "headerResponseTimeout must be positive"
            );
        }
        if (listenPort <= 0
                || listenPort > 65535) {

            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }

        this.listen =
                listen;

        this.listenPort =
                listenPort;
        liveSync = new ru.bitcoin.node.app.sync.LiveChainSynchronizer(peerManager,
                syncInfrastructure, blockSyncCoordinator, validationService, headerResponseTimeout);
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

            if (listen) {

                bitcoinServer.start(
                        listenPort,
                        startHeight
                );

                log.info(
                        "Bitcoin P2P server listening on port {}",
                        bitcoinServer.localPort()
                );
            }

            ensureNotStopping();

            /*
             * Keep the exact outbound connection that
             * successfully completed header synchronization.
             *
             * We must not later guess the outbound peer
             * from PeerManager because PeerManager may also
             * contain other peers.
             */
            OutboundPeerConnection activeOutboundConnection =
                    synchronizeHeadersWithFailover(
                            startHeight
                    );

            ensureNotStopping();

            /*
             * Header synchronization has completed successfully.
             *
             * Start long-lived outbound supervision before block IBD so
             * loss of the current peer does not make block synchronization
             * terminal merely because PeerManager temporarily contains
             * zero READY peers.
             *
             * The block-download scheduler keeps unfinished blocks pending
             * and will discover a replacement peer through PeerManager
             * after OutboundPeerSupervisor reconnects.
             */
            outboundPeerSupervisor.start(
                    activeOutboundConnection
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

            liveSync.run();

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
                HEADER_SYNC_STOP_HASH,
                batch -> { /* Headers are already persisted by HeaderSyncService. */ }
        );
    }

    private OutboundPeerConnection synchronizeHeadersWithFailover(
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

                /*
                 * Return the exact connection that successfully
                 * completed header synchronization.
                 */
                return connection;

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


        if (activeTip.chainWork().compareTo(minimumChainWork) < 0) {
            throw new IllegalStateException(
                    "Initial block synchronization ended below minimum chain work: activeChainWork="
                            + activeTip.chainWork().toString(16)
                            + ", minimumChainWork="
                            + minimumChainWork.toString(16)
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
        liveSync.close();

        NodeLifecycleState previousState;

        synchronized (this) {

            previousState =
                    state;

            failure =
                    exception;

            state =
                    NodeLifecycleState.FAILED;

            notifyAll();
        }

        log.error(
                "Bitcoin node state: {} -> FAILED",
                previousState,
                exception
        );

        try {

            bitcoinServer.close();

        } catch (IOException closeException) {

            exception.addSuppressed(
                    closeException
            );
        }

        /*
         * Stop reconnect activity before closing peers.
         *
         * Otherwise closing PeerManager could trigger
         * a peer-close callback while the supervisor
         * is still allowed to reconnect.
         */
        try {

            outboundPeerSupervisor.close();

        } catch (RuntimeException closeException) {

            exception.addSuppressed(
                    closeException
            );
        }

        try {

            blockSyncCoordinator.cancel();

        } catch (RuntimeException closeException) {

            exception.addSuppressed(
                    closeException
            );
        }

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

        liveSync.close();

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

            notifyAll();
        }

        log.info(
                "Bitcoin node state: {} -> STOPPING",
                previousState
        );

        IOException closeFailure =
                null;

        /*
         * Stop accepting inbound connections first.
         *
         * Otherwise an inbound handshake could complete
         * while the rest of the node is already shutting down
         * and add another peer to PeerManager.
         */
        try {

            bitcoinServer.close();

        } catch (IOException exception) {

            closeFailure =
                    new IOException(
                            "Failed to stop Bitcoin P2P server",
                            exception
                    );
        }

        /*
         * IMPORTANT:
         *
         * Stop the reconnect worker BEFORE PeerManager closes
         * the active peer.
         */
        try {

            outboundPeerSupervisor.close();

        } catch (RuntimeException exception) {

            closeFailure =
                    new IOException(
                            "Failed to stop outbound peer supervisor",
                            exception
                    );
        }

        try {

            blockSyncCoordinator.cancel();

        } catch (RuntimeException exception) {

            IOException cancellationFailure =
                    new IOException(
                            "Failed to cancel block synchronization",
                            exception
                    );

            if (closeFailure == null) {
                closeFailure = cancellationFailure;
            } else {
                closeFailure.addSuppressed(
                        cancellationFailure
                );
            }
        }

        try {

            peerManager.close();

        } catch (IOException exception) {

            if (closeFailure == null) {

                closeFailure =
                        exception;

            } else {

                closeFailure.addSuppressed(
                        exception
                );
            }
        }

        try {

            validationService.flushPersistentMempool();

        } catch (RuntimeException exception) {

            IOException mempoolFailure =
                    new IOException(
                            "Failed to persist mempool during shutdown",
                            exception
                    );

            if (closeFailure == null) {
                closeFailure = mempoolFailure;
            } else {
                closeFailure.addSuppressed(mempoolFailure);
            }
        }

        if (closeFailure == null) {

            synchronized (this) {

                state =
                        NodeLifecycleState.STOPPED;

                notifyAll();
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

                notifyAll();
            }

            log.error(
                    "Bitcoin node state: STOPPING -> FAILED",
                    closeFailure
            );

            throw closeFailure;
        }
    }
}
