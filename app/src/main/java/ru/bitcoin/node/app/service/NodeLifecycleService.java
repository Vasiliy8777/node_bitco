package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.HeaderSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.app.sync.SyncProgressConsole;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.address.PeerAddressManagerStore;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final Duration HEADER_RACE_RETRY_BACKOFF =
            Duration.ofMillis(100);

    private final NodeValidationService validationService;
    private final NodeSyncInfrastructure syncInfrastructure;

    private final PeerAddressManager addressManager;
    private final PeerAddressManagerStore addressManagerStore;
    private final PeerDiscovery peerDiscovery;
    private final OutboundPeerManager outboundPeerManager;
    private final OutboundPeerSupervisor outboundPeerSupervisor;
    private volatile boolean headerSupervisionStarted;
    private final PeerManager peerManager;

    private final BitcoinServer bitcoinServer;

    private final boolean listen;
    private final int listenPort;

    private final BlockSyncCoordinator blockSyncCoordinator;

    private final Duration headerResponseTimeout;
    private final BigInteger minimumChainWork;
    private final MempoolCheckpointService mempoolCheckpointService;

    private NodeLifecycleState state =
            NodeLifecycleState.NEW;

    private Throwable failure;

    /* Highest startHeight advertised by any header-sync peer in this run. */
    private final AtomicLong knownHeaderTargetHeight =
            new AtomicLong();
    private final ru.bitcoin.node.app.sync.LiveChainSynchronizer liveSync;
    private boolean automaticConnectionsEnabled = true;
    private boolean manualConnectionsConfigured;

    public synchronized void manualConnectionsConfigured(boolean configured) {
        if (state != NodeLifecycleState.NEW) throw new IllegalStateException("Configure networking before startup");
        manualConnectionsConfigured = configured;
    }

    public synchronized void automaticConnectionsEnabled(boolean enabled) {
        if (state != NodeLifecycleState.NEW) throw new IllegalStateException("Configure networking before startup");
        automaticConnectionsEnabled = enabled;
    }

    public boolean isMiningReady() {
        return (automaticConnectionsEnabled || manualConnectionsConfigured) && isRunning()
                && !validationService.downloadInitialBlockDownload()
                && liveSync.isCurrent();
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
        this(validationService, syncInfrastructure, addressManager, null, peerDiscovery, outboundPeerManager,
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
        this(validationService, syncInfrastructure, addressManager, null, peerDiscovery, outboundPeerManager,
                outboundPeerSupervisor, peerManager, bitcoinServer, blockSyncCoordinator,
                headerResponseTimeout, listen, listenPort, minimumChainWork);
    }

    public NodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerAddressManagerStore addressManagerStore,
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
        this(validationService, syncInfrastructure, addressManager, addressManagerStore, peerDiscovery,
                outboundPeerManager, outboundPeerSupervisor, peerManager, bitcoinServer, blockSyncCoordinator,
                headerResponseTimeout, listen, listenPort, minimumChainWork, Duration.ofMinutes(15));
    }

    public NodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerAddressManagerStore addressManagerStore,
            PeerDiscovery peerDiscovery,
            OutboundPeerManager outboundPeerManager,
            OutboundPeerSupervisor outboundPeerSupervisor,
            PeerManager peerManager,
            BitcoinServer bitcoinServer,
            BlockSyncCoordinator blockSyncCoordinator,
            Duration headerResponseTimeout,
            boolean listen,
            int listenPort,
            BigInteger minimumChainWork,
            Duration mempoolCheckpointInterval
    ) {
        this.validationService =
                Objects.requireNonNull(
                        validationService,
                        "validationService"
                );

        this.mempoolCheckpointService = new MempoolCheckpointService(
                validationService, Objects.requireNonNull(mempoolCheckpointInterval, "mempoolCheckpointInterval"));

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

        this.addressManagerStore = addressManagerStore;

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

        try {

            setState(
                    NodeLifecycleState.STARTING
            );

            if (!automaticConnectionsEnabled && !manualConnectionsConfigured) {
                if (listen) bitcoinServer.start(listenPort, Math.toIntExact(validationService.activeTip().height()));
                setState(NodeLifecycleState.RUNNING);
                mempoolCheckpointService.start();
                return;
            }

            if (automaticConnectionsEnabled) bootstrapAddresses();

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
            OutboundPeerConnection activeOutboundConnection = null;
            if (automaticConnectionsEnabled) activeOutboundConnection = synchronizeHeadersWithFailover(startHeight);
            else synchronizeManualHeaders();

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
            if (automaticConnectionsEnabled && !headerSupervisionStarted) outboundPeerSupervisor.start(activeOutboundConnection);

            ensureNotStopping();

            synchronizeBlocks();

            ensureNotStopping();

            synchronized (this) {

                if (state == NodeLifecycleState.STOPPING
                        || state == NodeLifecycleState.STOPPED) {

                    return;
                }
            }

            setState(
                    NodeLifecycleState.RUNNING
            );

            mempoolCheckpointService.start();
            liveSync.run();

        } catch (IOException | RuntimeException exception) {

            if (isStoppingOrStopped()) {
                return;
            }

            fail(
                    exception
            );

            throw exception;
        } catch (Error fatal) {
            fail(fatal);
            throw fatal;
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

        /*
         * During IBD, a non-empty AddrMan is not proof that we have a usable
         * synchronization peer. It may contain only stale/unreachable entries
         * (or an explicitly configured peer that is itself still in early IBD).
         *
         * Refresh DNS seeds while the active chain is still below the network's
         * minimum chain work. Once minimum work has been reached, preserve the
         * normal AddrMan-first startup path and avoid unnecessary DNS lookups.
         * Regtest has no DNS seeds, so this remains a no-op there.
         */
        if (!addressManager.isEmpty()
                && validationService.activeTip().chainWork().compareTo(minimumChainWork) >= 0) {
            return;
        }

        peerDiscovery.discover();
    }

    private long synchronizeHeaders(
            Peer peer
    ) throws IOException {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        setState(
                NodeLifecycleState.SYNCHRONIZING_HEADERS
        );

        HeaderSynchronizer headerSynchronizer = syncInfrastructure.headerSynchronizer(
                peer, peerManager, headerResponseTimeout, blockSyncCoordinator::blocksInFlight);

        HeaderSyncCoordinator coordinator =
                new HeaderSyncCoordinator(
                        headerSynchronizer,
                        syncInfrastructure.headerSyncService(),
                        syncInfrastructure.headerChainState(),
                        syncInfrastructure.blockLocatorBuilder()
                ).onNewValidatedHeader(index -> {
                    if (!syncInfrastructure.downloadBlockFailed(index)
                            && index.chainWork().compareTo(validationService.downloadTip().chainWork()) > 0)
                        peer.recordNewBlockAnnouncement();
                }).onValidatedBatch(batch -> {
                    if (!batch.isEmpty() && !syncInfrastructure.downloadBlockFailed(batch.getLast())
                            && batch.getLast().chainWork().compareTo(validationService.downloadTip().chainWork()) >= 0)
                        peerManager.protectChainSync(peer);
                });

        final long initialHeight = syncInfrastructure.headerChainState().bestHeaderTip().height();
        final long advertisedHeight = Math.max(initialHeight, peer.remoteVersion().startHeight());
        final long remoteTargetHeight = knownHeaderTargetHeight.updateAndGet(
                previous -> Math.max(previous, advertisedHeight)
        );

        SyncProgressConsole.headers(initialHeight, remoteTargetHeight);

        long processed = coordinator.synchronize(
                HEADER_SYNC_STOP_HASH,
                batch -> { },
                index -> SyncProgressConsole.headers(
                        index.height(),
                        remoteTargetHeight
                )
        );

        long finalHeight = syncInfrastructure.headerChainState().bestHeaderTip().height();

        /*
         * The progress renderer uses a carriage return and may otherwise leave
         * the penultimate per-header update visible when the following log line
         * is emitted concurrently. Publish the authoritative final chain-state
         * height once more before logging completion.
         */
        SyncProgressConsole.headersComplete(
                finalHeight,
                Math.max(remoteTargetHeight, finalHeight)
        );

        log.info(
                "Header sync completed at height {} (peer advertised target {})",
                finalHeight,
                remoteTargetHeight
        );
        return processed;
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
                                .connectHeaderPeerRace(
                                        startHeight,
                                        failedAddresses,
                                        4
                                );

            } catch (IOException connectFailure) {

                failure.addSuppressed(
                        connectFailure
                );

                if (isStoppingOrStopped()) {
                    throw connectFailure;
                }

                /*
                 * An exhausted race means only that this particular set of
                 * candidates failed right now. It is not a terminal IBD
                 * condition. AddrMan keeps attempt history and the next race
                 * can select a different/recovered endpoint. A genuinely empty
                 * AddrMan remains terminal so regtest/misconfiguration fails
                 * fast instead of spinning forever.
                 */
                if (isNoKnownHeaderPeerFailure(connectFailure)) {
                    throw failure;
                }

                log.warn(
                        "HEADER PEER RACE exhausted at bestHeaderHeight={}; retrying in {} ms: {}",
                        syncInfrastructure.headerChainState().bestHeaderTip().height(),
                        HEADER_RACE_RETRY_BACKOFF.toMillis(),
                        connectFailure.toString()
                );

                waitForHeaderRaceRetry();
                continue;
            }

            Peer peer =
                    connection.peer();

            if (headerResponseTimeout.equals(HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT)) {
                outboundPeerSupervisor.start(connection);
                headerSupervisionStarted = true;
                synchronizeCoreHeaders();
                return connection; // Supervisor already owns this seed and its replacement slot.
            }

            try {

                long processedHeaders = synchronizeHeaders(
                        peer
                );

                /*
                 * An empty HEADERS response is a valid end-of-chain signal only
                 * after the local best-header chain has reached the network's
                 * minimum chain work. Before that point it is not sufficient to
                 * declare header IBD complete: the peer may itself be in early
                 * IBD, stale, or otherwise unable to serve the required chain.
                 * Treat it as a peer-local synchronization failure and fail over
                 * without weakening the minimum-chain-work safety boundary.
                 */
                BlockIndex bestHeader = syncInfrastructure.headerChainState().bestHeaderTip();
                if (processedHeaders == 0
                        && bestHeader.chainWork().compareTo(minimumChainWork) < 0) {
                    throw new IOException(
                            "Header peer returned no progress below minimum chain work: "
                                    + "bestHeaderHeight=" + bestHeader.height()
                                    + ", bestHeaderChainWork=" + bestHeader.chainWork().toString(16)
                                    + ", minimumChainWork=" + minimumChainWork.toString(16)
                                    + ", remoteStartHeight=" + peer.remoteVersion().startHeight()
                    );
                }

                /*
                 * Return the exact connection that successfully
                 * completed header synchronization.
                 */
                return connection;

            } catch (ru.bitcoin.node.consensus.block.BlockHeaderValidationException
                     | IllegalArgumentException rejectedHeaders) {
                if (isStoppingOrStopped()) throw new IOException("Header synchronization cancelled", rejectedHeaders);
                failure.addSuppressed(new IOException("Peer sent rejected headers", rejectedHeaders));
                failedAddresses.add(connection.address());
                // A peer-local header rejection must not abort initial sync.
                // Future time is temporary; other failures use Peer's ordinary
                // protocol policy, including its NoBan/manual exemptions.
                if (!(rejectedHeaders instanceof ru.bitcoin.node.consensus.block.BlockHeaderValidationException invalid
                        && invalid.reason() == ru.bitcoin.node.consensus.block.BlockHeaderValidationException.Reason.TIME_FUTURE)) {
                    peer.disconnectForProtocolViolation("Rejected headers during initial synchronization", rejectedHeaders);
                }
                log.warn("HEADER SYNC rejected peer={} bestHeaderHeight={} cause={}; trying another source",
                        connection.address(), syncInfrastructure.headerChainState().bestHeaderTip().height(),
                        rejectedHeaders.toString());
            } catch (IOException syncFailure) {
                handleHeaderSyncPeerFailure(connection, peer, failedAddresses, failure, syncFailure);
            } catch (IllegalStateException stalePeerFailure) {
                /*
                 * PeerManager/background reader may observe a disconnect between
                 * connectOneWithAddress() and the first synchronous GETHEADERS.
                 * Treat that network race exactly like an IOException, but do not
                 * hide unrelated lifecycle/programming IllegalStateExceptions.
                 */
                if (peer.state() != PeerState.CLOSED) {
                    throw stalePeerFailure;
                }
                IOException syncFailure = new IOException(
                        "Peer closed before or during header synchronization",
                        stalePeerFailure
                );
                handleHeaderSyncPeerFailure(connection, peer, failedAddresses, failure, syncFailure);
            }
        }
    }


    private void synchronizeCoreHeaders() throws IOException {
        setState(NodeLifecycleState.SYNCHRONIZING_HEADERS);
        var tasks = new java.util.IdentityHashMap<Peer, java.util.concurrent.Future<Long>>();
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            while (true) {
                ensureNotStopping();
                for (var peer : peerManager.readyPeers()) {
                    if (!tasks.containsKey(peer) && (peer.remoteVersion().services() &
                            (ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED)) != 0)
                        tasks.put(peer, workers.submit(() -> synchronizeHeaders(peer)));
                }
                var iterator = tasks.entrySet().iterator();
                while (iterator.hasNext()) {
                    var task = iterator.next();
                    if (!task.getValue().isDone()) continue;
                    var peer = task.getKey(); var future = task.getValue(); iterator.remove();
                    try {
                        future.get();
                        if (syncInfrastructure.headerChainState().bestHeaderTip().chainWork().compareTo(minimumChainWork) >= 0) return;
                        throw new IOException("Header peer returned no progress below minimum chain work");
                    } catch (java.util.concurrent.ExecutionException failure) {
                        var cause = failure.getCause();
                        if (cause instanceof Error fatal) throw fatal;
                        if (cause instanceof IllegalStateException state && peer.state() != PeerState.CLOSED) throw state;
                        if (cause instanceof IllegalStateException && peer.state() == PeerState.CLOSED) continue;
                        if (!(cause instanceof IOException || cause instanceof IllegalArgumentException
                                || cause instanceof ru.bitcoin.node.consensus.block.BlockHeaderValidationException
                                || cause instanceof ru.bitcoin.node.consensus.block.BlockValidationException
                                || cause instanceof ru.bitcoin.node.consensus.transaction.TransactionValidationException))
                            throw new IOException("Header synchronization failed", cause);
                        if (cause instanceof ru.bitcoin.node.p2p.sync.HeaderSynchronizationTimeoutException
                                && peer.hasPermission(PeerPermission.NOBAN)) continue;
                        if (cause instanceof ru.bitcoin.node.consensus.block.BlockHeaderValidationException invalid) {
                            if (invalid.reason() != ru.bitcoin.node.consensus.block.BlockHeaderValidationException.Reason.TIME_FUTURE)
                                peer.disconnectForProtocolViolation("Rejected headers during initial synchronization", invalid);
                        } else peer.close();
                    } catch (IOException failure) { peer.close(); }
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Header synchronization cancelled", interrupted);
        } finally {
            tasks.values().forEach(task -> task.cancel(true)); workers.shutdownNow();
            try { workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private void synchronizeManualHeaders() throws IOException {
        if (headerResponseTimeout.equals(HeaderSynchronizer.DEFAULT_RESPONSE_TIMEOUT)) { synchronizeCoreHeaders(); return; }
        var retryAfter = new java.util.IdentityHashMap<Peer, Long>();
        setState(NodeLifecycleState.SYNCHRONIZING_HEADERS);
        while (true) {
            ensureNotStopping();
            var ready = peerManager.readyPeers();
            retryAfter.keySet().removeIf(peer -> !ready.contains(peer));
            for (Peer peer : ready) {
                if (!peer.isManualConnection() || System.nanoTime() < retryAfter.getOrDefault(peer, Long.MIN_VALUE)) continue;
                long services = peer.remoteVersion().services();
                if ((services & ru.bitcoin.node.p2p.message.VersionMessage.NODE_WITNESS) == 0
                        || (services & (ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK
                            | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED)) == 0) continue;
                try {
                    synchronizeHeaders(peer);
                    if (syncInfrastructure.headerChainState().bestHeaderTip().chainWork().compareTo(minimumChainWork) >= 0) return;
                    // A short/empty low-work reply does not finish initial sync.
                } catch (ru.bitcoin.node.consensus.block.BlockHeaderValidationException | IllegalArgumentException invalid) {
                    if (!(invalid instanceof ru.bitcoin.node.consensus.block.BlockHeaderValidationException rejected
                            && rejected.reason() == ru.bitcoin.node.consensus.block.BlockHeaderValidationException.Reason.TIME_FUTURE))
                        peer.disconnectForProtocolViolation("Rejected headers during manual synchronization", invalid);
                } catch (IOException failure) {
                    if (!(failure instanceof ru.bitcoin.node.p2p.sync.HeaderSynchronizationTimeoutException
                            && peer.hasPermission(PeerPermission.NOBAN))) {
                        try { peer.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                    }
                } catch (IllegalStateException failure) {
                    if (peer.state() != PeerState.CLOSED) throw failure;
                }
                // A reconnect is a new peer and can be tried immediately. An
                // unchanged connection cannot make a tight invalid/low-work loop.
                retryAfter.put(peer, System.nanoTime() + Duration.ofSeconds(5).toNanos());
            }
            waitForHeaderRaceRetry();
        }
    }

    private static boolean isNoKnownHeaderPeerFailure(Throwable failure) {
        if (failure == null) {
            return false;
        }
        String message = failure.getMessage();
        if (message != null && message.contains("No known peer addresses available")) {
            return true;
        }
        if (isNoKnownHeaderPeerFailure(failure.getCause())) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (isNoKnownHeaderPeerFailure(suppressed)) {
                return true;
            }
        }
        return false;
    }

    private void waitForHeaderRaceRetry() throws IOException {
        try {
            Thread.sleep(HEADER_RACE_RETRY_BACKOFF.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while waiting to retry header peer race",
                    interrupted
            );
        }
    }


    private void handleHeaderSyncPeerFailure(
            OutboundPeerConnection connection,
            Peer peer,
            List<PeerAddress> failedAddresses,
            IOException aggregateFailure,
            IOException syncFailure
    ) throws IOException {
        if (isStoppingOrStopped()) {
            throw syncFailure;
        }

        aggregateFailure.addSuppressed(syncFailure);
        failedAddresses.add(connection.address());
        // Switch header sources without closing or unregistering an open NoBan
        // connection. An unanswered request must not release GETHEADERS admission.
        if (syncFailure instanceof ru.bitcoin.node.p2p.sync.HeaderSynchronizationTimeoutException
                && peer.hasPermission(PeerPermission.NOBAN) && peer.state() != PeerState.CLOSED) {
            log.debug("HEADER SYNC timed out for NoBan peer={}; trying another source", connection.address());
            return;
        }
        peerManager.remove(peer);

        log.warn(
                "HEADER SYNC PEER FAILED peer={} state={} bestHeaderHeight={} cause={}",
                connection.address(),
                peer.state(),
                syncInfrastructure.headerChainState().bestHeaderTip().height(),
                syncFailure.toString()
        );

        try {
            peer.close();
        } catch (IOException closeFailure) {
            syncFailure.addSuppressed(closeFailure);
        }
    }

    private void synchronizeBlocks()
            throws IOException {

        setState(
                NodeLifecycleState.SYNCHRONIZING_BLOCKS
        );

        while (true) {
            ensureNotStopping();
            BlockIndex previousActiveTip = validationService.activeTip();
            BlockIndex previousHeaderTip = syncInfrastructure.headerChainState().bestHeaderTip();

            // A download round targets a snapshot of the header tip. Headers may
            // advance during a long IBD; catch up again before entering RUNNING.
            blockSyncCoordinator.synchronizeToTip();
            ensureNotStopping();

            synchronized (this) {
                if (verifySynchronized()) {
                    return;
                }
            }

            BlockIndex activeTip = validationService.activeTip();
            BlockIndex bestHeaderTip = syncInfrastructure.headerChainState().bestHeaderTip();
            if (activeTip.hash().equals(previousActiveTip.hash())
                    && bestHeaderTip.hash().equals(previousHeaderTip.hash())) {
                throw new IllegalStateException("Block synchronization made no progress: activeHeight="
                        + activeTip.height() + ", bestHeaderHeight=" + bestHeaderTip.height());
            }
            log.info("IBD target advanced; continuing block synchronization: activeHeight={} bestHeaderHeight={}",
                    activeTip.height(), bestHeaderTip.height());
        }
    }

    private boolean verifySynchronized() {

        BlockIndex activeTip =
                validationService.activeTip();

        BlockIndex bestHeaderTip =
                syncInfrastructure
                        .headerChainState()
                        .bestHeaderTip();

        if (!activeTip.hash().equals(
                bestHeaderTip.hash()
        )) {

            return false;
        }


        if (activeTip.chainWork().compareTo(minimumChainWork) < 0) {
            throw new IllegalStateException(
                    "Initial block synchronization ended below minimum chain work: activeChainWork="
                            + activeTip.chainWork().toString(16)
                            + ", minimumChainWork="
                            + minimumChainWork.toString(16)
            );
        }
        return true;
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

            notifyAll();
        }

        log.error(
                "Bitcoin node state: {} -> FAILED",
                previousState,
                exception
        );

        try {
            liveSync.close();
        } catch (RuntimeException closeException) {
            exception.addSuppressed(closeException);
        }

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

        // Stop periodic mempool writes before the final coherent shutdown snapshot.
        mempoolCheckpointService.close();

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
         * Abort a peer that is still inside TCP/BIP324/VERSION connection
         * establishment. Such a peer is not in PeerManager yet, so closing the
         * manager alone cannot unblock the lifecycle worker.
         */
        outboundPeerManager.cancelPendingConnections();

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

        if (addressManagerStore != null) {
            try {
                addressManagerStore.save(addressManager);
            } catch (IOException exception) {
                IOException addrManFailure = new IOException(
                        "Failed to persist AddrMan during shutdown", exception);
                if (closeFailure == null) closeFailure = addrManFailure;
                else closeFailure.addSuppressed(addrManFailure);
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
