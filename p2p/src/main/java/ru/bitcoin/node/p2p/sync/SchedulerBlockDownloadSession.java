package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class SchedulerBlockDownloadSession
        implements BlockDownloadSession {

    private static final System.Logger log =
            System.getLogger(SchedulerBlockDownloadSession.class.getName());

    private static final int FRONTIER_DIAGNOSTIC_STATE_LIMIT = 0;
    private volatile BlockDownloadPeerPolicy peerPolicy = BlockDownloadPeerPolicy.SERVICES_ONLY;

    synchronized void peerPolicy(BlockDownloadPeerPolicy policy) {
        peerPolicy = Objects.requireNonNull(policy);
    }

    private final PeerManager peerManager;
    private final BlockDownloadService blockDownloadService;

    private static final Duration COMPLETION_CHECK_INTERVAL =
            Duration.ofMillis(
                    250
            );


    private final BlockDownloadTimeoutPolicy timeoutPolicy;

    private final BlockDownloadTimeoutEvaluator timeoutEvaluator;

    // Keep only unfinished work; scanning completed history makes a long IBD quadratic.
    private final Map<Hash256, DownloadState> states =
            new LinkedHashMap<>();

    private final Set<Hash256> submittedHashes =
            new HashSet<>();

    private final BlockInFlightTracker inFlightTracker;

    private final IdentityHashMap<
            CompletableFuture<DownloadResult>,
            ActiveDownload
            > activeDownloads =
            new IdentityHashMap<>();

    /**
     * Futures are completed by peer-reader/dispatcher callbacks. This queue is
     * only a readiness notification mechanism; no worker thread waits per block.
     */
    private final BlockingQueue<CompletableFuture<DownloadResult>>
            completionQueue = new LinkedBlockingQueue<>();

    private final IdentityHashMap<Peer, PeerBlockDownloadState> peerStates = new IdentityHashMap<>();
    private int nextIndex;
    private int pendingCount;
    private int nextPeerIndex;

    private static final long PEER_DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private long nextPeerDiagnosticNanos;

    private boolean closed;
    private final java.util.function.Consumer<SchedulerBlockDownloadSession> closeListener;
    private final Deque<CompletedBlockDownload> externalCompletions = new ArrayDeque<>();

    public SchedulerBlockDownloadSession(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {
        this(peerManager, blockDownloadService, timeoutPolicy, ignored -> { },
                BlockDownloadScheduler.MAX_BLOCKS_IN_FLIGHT_PER_PEER);
    }

    SchedulerBlockDownloadSession(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy,
            java.util.function.Consumer<SchedulerBlockDownloadSession> closeListener,
            int maxBlocksInFlightPerPeer
    ) {
        this(peerManager, blockDownloadService, timeoutPolicy, closeListener,
                new BlockInFlightTracker(maxBlocksInFlightPerPeer));
    }

    SchedulerBlockDownloadSession(PeerManager peerManager, BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy,
            java.util.function.Consumer<SchedulerBlockDownloadSession> closeListener,
            BlockInFlightTracker inFlightTracker) {

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.blockDownloadService =
                Objects.requireNonNull(
                        blockDownloadService,
                        "blockDownloadService"
                );

        this.timeoutPolicy =
                Objects.requireNonNull(
                        timeoutPolicy,
                        "timeoutPolicy"
                );

        this.inFlightTracker = Objects.requireNonNull(inFlightTracker);

        this.timeoutEvaluator =
                new BlockDownloadTimeoutEvaluator(
                        inFlightTracker,
                        timeoutPolicy
                );

        this.closeListener =
                Objects.requireNonNull(
                        closeListener,
                        "closeListener"
                );
    }

    @Override
    public void submit(
            List<Hash256> blockHashes
    ) throws IOException {
        Objects.requireNonNull(blockHashes, "blockHashes");
        refreshPeerPolicy();
        synchronized (this) {
            submitInternal(blockHashes.stream()
                    .map(hash -> new PendingRequest(hash, null))
                    .toList());
        }
    }

    @Override
    public void submitRequests(
            List<BlockDownloadRequest> requests
    ) throws IOException {
        Objects.requireNonNull(requests, "requests");
        refreshPeerPolicy();
        synchronized (this) {
            submitInternal(requests.stream()
                    .map(request -> new PendingRequest(request.blockHash(), request.height()))
                    .toList());
        }
    }

    private void submitInternal(
            List<PendingRequest> requests
    ) throws IOException {

        ensureOpen();

        Set<Hash256> batchHashes = new HashSet<>();

        for (PendingRequest request : requests) {
            Objects.requireNonNull(request, "requests must not contain null");
            Hash256 blockHash = request.blockHash();

            if (!batchHashes.add(blockHash)) {
                throw new IllegalArgumentException(
                        "Duplicate block hash in submitted batch: "
                                + blockHash.toDisplayHex()
                );
            }

            if (submittedHashes.contains(blockHash)) {
                throw new IllegalArgumentException(
                        "Block hash was already submitted to this session: "
                                + blockHash.toDisplayHex()
                );
            }
        }

        if (requests.isEmpty()) {
            return;
        }

        List<Peer> peers = peerManager.readyPeers();

        int prospectiveNextIndex =
                Math.addExact(nextIndex, requests.size());

        int prospectivePendingCount =
                Math.addExact(pendingCount, requests.size());

        int index = nextIndex;

        for (PendingRequest request : requests) {
            states.put(request.blockHash(),
                    new DownloadState(
                            index,
                            request.blockHash(),
                            request.height()
                    )
            );

            submittedHashes.add(request.blockHash());
            index = Math.incrementExact(index);
        }

        nextIndex = prospectiveNextIndex;
        pendingCount = prospectivePendingCount;

        if (!peers.isEmpty()) {
            assignAvailable(peers);
        }
    }

    @Override
    public CompletedBlockDownload awaitCompleted()
            throws IOException {

        while (true) {

            Optional<CompletedBlockDownload> completed =
                    pollCompleted(
                            COMPLETION_CHECK_INTERVAL
                    );

            if (completed.isPresent()) {
                return completed.get();
            }
        }
    }

    @Override
    public Optional<CompletedBlockDownload> pollCompleted(
            Duration timeout
    ) throws IOException {

        Objects.requireNonNull(
                timeout,
                "timeout"
        );

        if (timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must not be negative"
            );
        }

        refreshPeerPolicy();

        CompletableFuture<DownloadResult> future;

        boolean waitForReadyPeer = false;

        synchronized (this) {

            ensureOpen();

            CompletedBlockDownload external =
                    externalCompletions.pollFirst();

            if (external != null) {
                pendingCount = Math.decrementExact(pendingCount);
                return Optional.of(external);
            }

            if (pendingCount == 0) {
                throw new IllegalStateException(
                        "No pending block downloads"
                );
            }

            List<Peer> peers =
                    peerManager.readyPeers();

            if (!peers.isEmpty()) {

                assignAvailable(
                        peers
                );
            }

            if (activeDownloads.isEmpty()) {

                DownloadState failedState =
                        firstIncomplete();

                if (failedState == null) {
                    throw new IllegalStateException(
                            "Pending block count is inconsistent with download states"
                    );
                }

                /*
                 * IMPORTANT:
                 *
                 * Do not use the peer snapshot captured before assignAvailable().
                 *
                 * A peer can disconnect between peerManager.readyPeers() and this
                 * point. In that case assignAvailable() correctly skips the now
                 * CLOSED peer, but the old snapshot still contains it.
                 *
                 * Using that stale snapshot here would incorrectly turn a temporary
                 * zero-peer reconnect window into a terminal block-download failure.
                 */
                List<Peer> currentReadyPeers =
                        peerManager.readyPeers();

                if (currentReadyPeers.isEmpty()) {

                    /*
                     * No READY peers exist right now.
                     *
                     * This is recoverable: OutboundPeerSupervisor may install a
                     * replacement Peer. Keep the incomplete block pending and allow
                     * the next poll iteration to assign it to that new Peer instance.
                     */
                    waitForReadyPeer = true;

                } else if (failedState.awaitingAlternativePeer
                        || currentReadyPeers.stream().anyMatch(peer -> !failedState.attemptedPeers.contains(peer))
                        || inFlightTracker.peerForBlock(failedState.blockHash) != null) {

                    /*
                     * A request-scoped frontier rescue deliberately excludes the peer
                     * that just stalled.  OutboundPeerSupervisor may still be connecting
                     * a replacement while that old peer remains READY for other work.
                     *
                     * A new READY peer can still be proving block availability through
                     * HEADERS, or its slots/hash can be owned by another session.
                     * Only peers that actually failed this request are exhausted.
                     * Keep pending work until availability or ownership changes.
                     */
                    waitForReadyPeer = true;

                } else {

                    /*
                     * READY peers still genuinely exist and this state is not waiting
                     * for a post-rescue replacement. assignAvailable() has already had
                     * an opportunity to assign the incomplete work, so all currently
                     * eligible peers have been exhausted (for example they returned
                     * NOTFOUND). Preserve terminal failure for that case.
                     */
                    throw buildFailure(
                            failedState
                    );
                }
            }
        }

        if (waitForReadyPeer) {
            return waitWithoutReadyPeer(
                    timeout
            );
        }

        try {

            future =
                    completionQueue.poll(
                            timeout.toNanos(),
                            TimeUnit.NANOSECONDS
                    );

        } catch (ArithmeticException exception) {

            throw new IllegalArgumentException(
                    "timeout is too large",
                    exception
            );

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IOException(
                    "Block download interrupted",
                    exception
            );
        }

        if (future == null) {

            synchronized (this) {

                ensureOpen();

                failTimedOutPeers();

                List<Peer> peers =
                        peerManager.readyPeers();

                if (!peers.isEmpty()) {
                    assignAvailable(
                            peers
                    );
                }
            }

            return Optional.empty();
        }

        ActiveDownload activeDownload;

        synchronized (this) {

            activeDownload =
                    activeDownloads.remove(
                            future
                    );

            if (activeDownload == null) {

                /*
                 * A peer-wide cleanup may already have removed
                 * ownership before this cancelled Future reached
                 * CompletionService.
                 */
                return Optional.empty();
            }
        }

        DownloadResult result;

        try {

            result =
                    future.join();

        } catch (CancellationException exception) {

            throw new IOException(
                    "Registered block download task was unexpectedly cancelled",
                    exception
            );

        } catch (CompletionException exception) {

            throw new IOException(
                    "Unexpected asynchronous block download failure",
                    exception.getCause()
            );
        }

        synchronized (this) {

            if (result.peer()
                    != activeDownload.peer()) {

                throw new IOException(
                        "Completed block download task returned a different peer"
                );
            }

            if (result.state()
                    != activeDownload.state()) {

                throw new IOException(
                        "Completed block download task returned a different download state"
                );
            }

            Peer peer =
                    result.peer();

            DownloadState state =
                    result.state();

            state.inFlight =
                    false;

            inFlightTracker.remove(
                    peer,
                    state.blockHash
            );

            if (result.failure() != null) {

                state.failures.add(result.failure());
                state.awaitingAlternativePeer = !peer.isReady();
                peerStates.computeIfAbsent(peer, PeerBlockDownloadState::new).failed(System.nanoTime());

                List<Peer> peers =
                        peerManager.readyPeers();

                if (!peers.isEmpty()) {
                    assignAvailable(
                            peers
                    );
                }

                return Optional.empty();
            }

            Block block =
                    result.block();

            if (!state.blockHash.equals(
                    block.hash()
            )) {

                IOException failure =
                        new IOException(
                                "Peer returned unexpected block: expected "
                                        + state.blockHash.toDisplayHex()
                                        + ", actual "
                                        + block.hash().toDisplayHex()
                        );

                state.failures.add(
                        failure
                );

                List<Peer> peers =
                        peerManager.readyPeers();

                if (!peers.isEmpty()) {
                    assignAvailable(
                            peers
                    );
                }

                return Optional.empty();
            }

            peerStates.computeIfAbsent(peer, PeerBlockDownloadState::new)
                    .completed(state.height, System.nanoTime() - state.assignedAtNanos);

            if (state.completed) {
                throw new IllegalStateException(
                        "Block download completed more than once: "
                                + state.blockHash.toDisplayHex()
                );
            }

            state.completed =
                    true;
            states.remove(state.blockHash);

            pendingCount =
                    Math.decrementExact(
                            pendingCount
                    );

            List<Peer> peers =
                    peerManager.readyPeers();

            if (!peers.isEmpty()) {
                assignAvailable(
                        peers
                );
            }

            if (state.index < FRONTIER_DIAGNOSTIC_STATE_LIMIT) {
                log.log(
                        System.Logger.Level.INFO,
                        "IBD completion delivered: index={0}, height={1}, hash={2}, pending={3}",
                        state.index,
                        state.height,
                        state.blockHash.toDisplayHex(),
                        pendingCount
                );
            }

            return Optional.of(
                    new CompletedBlockDownload(
                            state.index,
                            state.blockHash,
                            block,
                            peer
                    )
            );
        }
    }

    private Optional<CompletedBlockDownload> waitWithoutReadyPeer(
            Duration timeout
    ) throws IOException {

        if (timeout.isZero()) {
            return Optional.empty();
        }

        final long timeoutNanos;

        try {

            timeoutNanos =
                    timeout.toNanos();

        } catch (ArithmeticException exception) {

            throw new IllegalArgumentException(
                    "timeout is too large",
                    exception
            );
        }

        long timeoutMillis =
                TimeUnit.NANOSECONDS.toMillis(
                        timeoutNanos
                );

        int nanosRemainder =
                (int) (
                        timeoutNanos
                                - TimeUnit.MILLISECONDS.toNanos(
                                timeoutMillis
                        )
                );

        synchronized (this) {

            ensureOpen();

            try {

                wait(
                        timeoutMillis,
                        nanosRemainder
                );

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IOException(
                        "Block download interrupted",
                        exception
                );
            }

            ensureOpen();
        }

        return Optional.empty();
    }

    @Override
    public synchronized int pendingCount() {
        return pendingCount;
    }

    /**
     * Returns whether this session still owns an unfinished download for the
     * supplied block hash, regardless of whether a peer has already been
     * assigned.
     */
    synchronized boolean hasPendingBlock(
            Hash256 blockHash
    ) {
        Objects.requireNonNull(blockHash, "blockHash");

        return states.containsKey(blockHash);
    }

    /**
     * Returns whether this active session has submitted the supplied block hash,
     * including a block that has already been completed but has not yet left
     * the session lifecycle. Alternative transports use this to suppress late
     * duplicate delivery through a second validation path.
     */
    synchronized boolean hasSubmittedBlock(
            Hash256 blockHash
    ) {
        Objects.requireNonNull(blockHash, "blockHash");

        return submittedHashes.contains(blockHash);
    }

    @Override
    public void close() {

        List<CompletableFuture<DownloadResult>> futures;

        synchronized (this) {

            if (closed) {
                return;
            }

            closed = true;

            /*
             * Wake pollCompleted() immediately if it is currently
             * waiting for a replacement READY peer.
             */
            notifyAll();

            futures =
                    new ArrayList<>(
                            activeDownloads.keySet()
                    );

            for (var entry : activeDownloads.entrySet()) {
                entry.getKey().cancel(true);
                ActiveDownload active = entry.getValue();
                inFlightTracker.remove(active.peer(), active.state().blockHash);
                active.state().inFlight = false;
            }
            activeDownloads.clear();

        }

        for (CompletableFuture<DownloadResult> future :
                futures) {

            future.cancel(
                    true
            );
        }


        closeListener.accept(
                this
        );
    }

    /**
     * Satisfies a submitted block from an alternative transport path.
     * If an ordinary GETDATA request is already in flight, its dispatcher
     * future is completed so the existing worker and in-flight accounting
     * finish normally. If it has not yet been assigned, completion is queued
     * directly without consuming an in-flight slot.
     */
    synchronized boolean acceptExternalBlock(
            Peer sourcePeer,
            Block block
    ) {
        Objects.requireNonNull(sourcePeer, "sourcePeer");
        Objects.requireNonNull(block, "block");

        if (closed) {
            return false;
        }

        DownloadState state = states.get(block.hash());

        if (state == null) {
            return false;
        }

        /*
         * Complete the logical scheduler state here, atomically under the
         * session monitor.
         *
         * Do not leave the state marked in-flight until the ordinary worker
         * happens to return. Otherwise a peer disconnect/timeout can race with
         * an already accepted compact block and incorrectly turn that success
         * into a retry/failure.
         */
        if (state.inFlight) {

            Peer assignedPeer =
                    inFlightTracker.peerForBlock(
                            state.blockHash
                    );

            if (assignedPeer == null) {
                throw new IllegalStateException(
                        "In-flight state has no owning peer"
                );
            }

            CompletableFuture<DownloadResult> ordinaryFuture =
                    activeFutureForState(
                            state
                    );

            if (ordinaryFuture == null) {
                throw new IllegalStateException(
                        "In-flight state has no active download task"
                );
            }

            /*
             * Remove ordinary ownership before waking its dispatcher future.
             * The worker is intentionally NOT cancelled: CompletableFuture.join()
             * is not an interruptible wait. Supplying the block lets that worker
             * unwind normally. Its later CompletionService entry is stale and is
             * ignored because activeDownloads no longer owns the Future.
             */
            activeDownloads.remove(
                    ordinaryFuture
            );

            inFlightTracker.remove(
                    assignedPeer,
                    state.blockHash
            );

            state.inFlight = false;
            state.completed = true;
            states.remove(state.blockHash);

            externalCompletions.addLast(
                    new CompletedBlockDownload(
                            state.index,
                            state.blockHash,
                            block,
                            sourcePeer
                    )
            );

            /*
             * This also covers the narrow race where the scheduler has marked
             * the hash in-flight but BlockSynchronizer has not registered its
             * dispatcher future yet: PeerMessageDispatcher retains that early
             * completion for the imminent registration.
             */
            assignedPeer.messageDispatcher()
                    .completePendingBlock(
                            block
                    );

        } else {

            state.completed = true;
            states.remove(state.blockHash);

            externalCompletions.addLast(
                    new CompletedBlockDownload(
                            state.index,
                            state.blockHash,
                            block,
                            sourcePeer
                    )
            );
        }

        /*
         * Releasing an in-flight slot may make more submitted work immediately
         * assignable. Keep the scheduler work-conserving.
         */
        List<Peer> readyPeers =
                peerManager.readyPeers();

        if (!readyPeers.isEmpty()) {
            assignAvailable(
                    readyPeers
            );
        }

        notifyAll();
        return true;
    }

    private CompletableFuture<DownloadResult> activeFutureForState(
            DownloadState state
    ) {

        for (var entry : activeDownloads.entrySet()) {
            if (entry.getValue().state() == state) {
                return entry.getKey();
            }
        }

        return null;
    }

    private void assignAvailable(
            List<Peer> peers
    ) {

        while (activeDownloads.size() < BlockDownloadScheduler.MAX_TOTAL_BLOCKS_IN_FLIGHT) {
            Assignment assignment = nextAssignment(peers);
            if (assignment == null) return;
            int peerIndex = assignment.peerIndex();
            Peer peer = peers.get(peerIndex);
            DownloadState state = assignment.state();
            if (!inFlightTracker.tryRegister(peer, state.blockHash)) continue;
            state.inFlight = true;
            state.assignedAtNanos = System.nanoTime();
            peerStates.computeIfAbsent(peer, PeerBlockDownloadState::new)
                    .assigned(state.assignedAtNanos);
            state.awaitingAlternativePeer = false;

            state.attemptedPeers.add(
                    peer
            );

            CompletableFuture<DownloadResult> future;

            try {

                future =
                        downloadAsync(
                                peer,
                                state
                        );

                future.whenComplete(
                        (ignoredResult, ignoredFailure) ->
                                completionQueue.offer(future)
                );

            } catch (RuntimeException exception) {

                inFlightTracker.remove(
                        peer,
                        state.blockHash
                );

                state.inFlight =
                        false;

                state.attemptedPeers.remove(
                        peer
                );

                throw exception;
            }

            activeDownloads.put(
                    future,
                    new ActiveDownload(
                            peer,
                            state
                    )
            );

            maybeLogPeerDistribution(peers);

            if (state.index < FRONTIER_DIAGNOSTIC_STATE_LIMIT) {
                log.log(
                        System.Logger.Level.INFO,
                        "IBD scheduler assigned: index={0}, height={1}, hash={2}, peer={3}, active={4}, pending={5}",
                        state.index,
                        state.height,
                        state.blockHash.toDisplayHex(),
                        diagnosticPeerAddress(peer),
                        activeDownloads.size(),
                        pendingCount
                );
            }

            nextPeerIndex =
                    inFlightTracker.canRegister(peer) ? peerIndex : (peerIndex + 1) % peers.size();

        }
    }

    private void maybeLogPeerDistribution(List<Peer> peers) {
        long now = System.nanoTime();
        if (now < nextPeerDiagnosticNanos) return;
        nextPeerDiagnosticNanos = now + PEER_DIAGNOSTIC_INTERVAL_NANOS;
        StringBuilder details = new StringBuilder();
        int eligible = 0;
        int downloading = 0;
        for (Peer peer : peers) {
            if (!peer.isReady()) continue;
            PeerBlockDownloadState peerState = peerStates.computeIfAbsent(peer, PeerBlockDownloadState::new);
            int inFlight = inFlightTracker.count(peer);
            if (inFlight > 0) downloading++;
            if (peerState.canServe(null, now)) eligible++;
            if (details.length() > 0) details.append("; ");
            details.append(diagnosticPeerAddress(peer))
                    .append(" role=").append(diagnosticPeerRole(peer))
                    .append(" inFlight=").append(inFlight)
                    .append(" ok=").append(peerState.completedBlocks())
                    .append(" fail=").append(peerState.failedBlocks())
                    .append(" latencyMs=").append(TimeUnit.NANOSECONDS.toMillis(peerState.latencyEwmaNanos()));
        }
        log.log(System.Logger.Level.INFO,
                "IBD BLOCK PEERS: ready={0}, eligible={1}, downloading={2}, active={3}, pending={4}: {5}",
                peers.size(), eligible, downloading, activeDownloads.size(), pendingCount, details);
    }

    private String diagnosticPeerRole(Peer peer) {
        try { return peerManager.roleOf(peer).name(); }
        catch (RuntimeException ignored) { return "UNMANAGED"; }
    }

    /** Fill each peer's queue with its earliest eligible requests, as in Core SendMessages. */
    private Assignment nextAssignment(List<Peer> peers) {
        // Core's SendMessages fills the currently visited peer's queue before
        // visiting another peer. No latency scoring or frontier replication.
        for (int offset = 0; offset < peers.size(); offset++) {
            int peerIndex = (nextPeerIndex + offset) % peers.size();
            Peer peer = peers.get(peerIndex);
            if (!peer.isReady() || !inFlightTracker.canRegister(peer)) continue;
            for (DownloadState state : states.values()) {
                if (!state.completed && !state.inFlight && inFlightTracker.peerForBlock(state.blockHash) == null
                        && !state.attemptedPeers.contains(peer)
                        && peerCanServe(peer, state)) return new Assignment(state, peerIndex);
            }
        }
        return null;
    }

    private record Assignment(DownloadState state, int peerIndex) { }
    private boolean peerCanServe(
            Peer peer,
            DownloadState state
    ) {
        return peerPolicy.canDownload(peer, state.blockHash, state.height);
    }

    private DownloadState firstIncomplete() {

        for (DownloadState state :
                states.values()) {

            if (!state.completed) {
                return state;
            }
        }

        return null;
    }

    private CompletableFuture<DownloadResult> downloadAsync(
            Peer peer,
            DownloadState state
    ) {
        var transport = blockDownloadService.downloadAsync(peer, state.blockHash, true);
        var result = transport.handle((block, failure) -> {
                    if (failure == null) {
                        return DownloadResult.success(peer, state, block);
                    }

                    Throwable cause = unwrapCompletionFailure(failure);
                    IOException ioFailure;

                    if (cause instanceof IOException ioException) {
                        ioFailure = ioException;
                    } else {
                        ioFailure = new IOException(
                                "Asynchronous block download failed",
                                cause
                        );
                    }

                    return DownloadResult.failure(peer, state, ioFailure);
                });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) transport.cancel(true);
        });
        return result;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Diagnostic logging must never change scheduler semantics. A peer may
     * become CLOSED between the network operation and the log statement;
     * Peer.remoteAddress() then legitimately throws IllegalStateException.
     */
    private static String diagnosticPeerAddress(
            Peer peer
    ) {
        try {
            return String.valueOf(
                    peer.remoteAddress()
            );
        } catch (RuntimeException exception) {
            return "<disconnected>";
        }
    }

    private IOException buildFailure(
            DownloadState state
    ) {

        IOException failure =
                new IOException(
                        "Unable to download block "
                                + state.blockHash.toDisplayHex()
                );

        for (IOException attemptFailure :
                state.failures) {

            failure.addSuppressed(
                    attemptFailure
            );
        }

        return failure;
    }

    private void ensureOpen()
            throws IOException {

        if (closed) {
            throw new IOException(
                    "Block download session is closed"
            );
        }
    }

    private record PendingRequest(
            Hash256 blockHash,
            Long height
    ) {
        private PendingRequest {
            Objects.requireNonNull(blockHash, "blockHash");
            if (height != null && height < 0) {
                throw new IllegalArgumentException("height must not be negative");
            }
        }
    }

    private static final class DownloadState {

        private final int index;
        private final Hash256 blockHash;
        private final Long height;

        private final Set<Peer> attemptedPeers =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        private final List<IOException> failures =
                new ArrayList<>();

        private boolean inFlight;
        private long assignedAtNanos;
        /*
         * True only after request-scoped frontier rescue released this block from a
         * stalling owner.  While true, an old READY-but-already-attempted peer must
         * not make pollCompleted() declare terminal exhaustion before the outbound
         * supervisor has a chance to install a replacement peer.
         */
        private boolean awaitingAlternativePeer;
        private boolean completed;

        private DownloadState(
                int index,
                Hash256 blockHash,
                Long height
        ) {

            if (index < 0) {
                throw new IllegalArgumentException(
                        "index must not be negative"
                );
            }

            this.index =
                    index;

            this.blockHash =
                    Objects.requireNonNull(
                            blockHash,
                            "blockHash"
                    );

            if (height != null && height < 0) {
                throw new IllegalArgumentException("height must not be negative");
            }
            this.height = height;
        }
    }

    private record ActiveDownload(
            Peer peer,
            DownloadState state
    ) {

        private ActiveDownload {

            Objects.requireNonNull(
                    peer,
                    "peer"
            );

            Objects.requireNonNull(
                    state,
                    "state"
            );
        }
    }

    private record DownloadResult(
            Peer peer,
            DownloadState state,
            Block block,
            IOException failure
    ) {

        private DownloadResult {

            Objects.requireNonNull(
                    peer,
                    "peer"
            );

            Objects.requireNonNull(
                    state,
                    "state"
            );

            if ((block == null)
                    == (failure == null)) {

                throw new IllegalArgumentException(
                        "Exactly one of block or failure must be present"
                );
            }
        }

        private static DownloadResult success(
                Peer peer,
                DownloadState state,
                Block block
        ) {

            return new DownloadResult(
                    peer,
                    state,
                    Objects.requireNonNull(
                            block,
                            "block"
                    ),
                    null
            );
        }

        private static DownloadResult failure(
                Peer peer,
                DownloadState state,
                IOException failure
        ) {

            return new DownloadResult(
                    peer,
                    state,
                    null,
                    Objects.requireNonNull(
                            failure,
                            "failure"
                    )
            );
        }
    }

    private List<CompletableFuture<DownloadResult>>
    activeDownloadsForPeer(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        List<CompletableFuture<DownloadResult>> futures =
                new ArrayList<>();

        for (var entry :
                activeDownloads.entrySet()) {

            if (entry.getValue()
                    .peer() == peer) {

                futures.add(
                        entry.getKey()
                );
            }
        }

        return List.copyOf(
                futures
        );
    }

    private int failPeerDownloads(
            Peer peer,
            IOException failure
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                failure,
                "failure"
        );

        List<CompletableFuture<DownloadResult>> futures =
                activeDownloadsForPeer(
                        peer
                );

        if (futures.isEmpty()) {
            return 0;
        }

        /*
         * Release tracker ownership first.
         *
         * Every removed hash immediately becomes eligible
         * for assignment to another peer.
         */
        Set<Hash256> removedBlocks = new HashSet<>();
        for (var future : futures) {
            var active = activeDownloads.get(future);
            future.cancel(true); // Unregister transport before publishing a free hash/slot.
            inFlightTracker.remove(peer, active.state().blockHash);
            removedBlocks.add(active.state().blockHash);
        }

        int released = 0;

        for (CompletableFuture<DownloadResult> future :
                futures) {

            ActiveDownload activeDownload =
                    activeDownloads.remove(
                            future
                    );

            if (activeDownload == null) {
                continue;
            }

            DownloadState state =
                    activeDownload.state();

            if (!removedBlocks.contains(
                    state.blockHash
            )) {
                throw new IllegalStateException(
                        "Active download is missing from in-flight tracker: "
                                + state.blockHash.toDisplayHex()
                );
            }

            /*
             * Remove session ownership BEFORE cancellation.
             *
             * If CompletionService later emits this Future,
             * awaitCompleted() will recognize it as stale.
             */
            state.inFlight =
                    false;

            state.awaitingAlternativePeer = true;

            state.failures.add(
                    failure
            );

            future.cancel(
                    true
            );

            released =
                    Math.incrementExact(
                            released
                    );
        }

        if (released
                != removedBlocks.size()) {

            throw new IllegalStateException(
                    "Active download count does not match in-flight tracker count"
            );
        }

        return released;
    }

    private record PeerTimeoutEvaluation(
            Peer peer,
            BlockDownloadTimeoutEvaluator.Evaluation evaluation
    ) {

        private PeerTimeoutEvaluation {

            Objects.requireNonNull(
                    peer,
                    "peer"
            );

            Objects.requireNonNull(
                    evaluation,
                    "evaluation"
            );
        }
    }

    private int failTimedOutPeers() {

        /*
         * Keep the original peer snapshot for the entire timeout tick.
         *
         * Evaluation must happen before any tracker mutation because
         * the timeout budget depends on otherDownloadingPeerCount().
         */
        List<Peer> peers =
                peerManager.peers();

        List<PeerTimeoutEvaluation> evaluations =
                new ArrayList<>(
                        peers.size()
                );

        for (Peer peer :
                peers) {

            BlockDownloadTimeoutEvaluator.Evaluation evaluation =
                    timeoutEvaluator.evaluate(
                            peer
                    );

            evaluations.add(
                    new PeerTimeoutEvaluation(
                            peer,
                            evaluation
                    )
            );
        }

        int released = 0;

        for (PeerTimeoutEvaluation peerEvaluation :
                evaluations) {

            BlockDownloadTimeoutEvaluator.Evaluation evaluation =
                    peerEvaluation.evaluation();

            if (!evaluation.downloading()
                    || !evaluation.timedOut()) {
                continue;
            }

            Peer peer =
                    peerEvaluation.peer();

            IOException failure = new ru.bitcoin.node.p2p.PeerCloseException(
                    ru.bitcoin.node.p2p.PeerCloseReason.LOCAL_BLOCK_TIMEOUT,
                    "SchedulerBlockDownloadSession.downloadTimeout",
                    "Peer block download timeout after " + evaluation.downloadingAge()
                            + " (timeout " + evaluation.timeout() + ")");
            try {
                peer.close(failure);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            int peerReleased = failPeerDownloads(peer, failure);
            released = Math.addExact(released, peerReleased);
            log.log(System.Logger.Level.WARNING,
                    "IBD peer block timeout: peer={0}, released={1}",
                    diagnosticPeerAddress(peer), peerReleased);
        }

        return released;
    }

    @Override
    public boolean retryBlock(Hash256 blockHash, Peer expectedPeer, IOException failure) throws IOException {
        refreshPeerPolicy();
        return retryBlockInternal(blockHash, expectedPeer, failure);
    }

    private synchronized boolean retryBlockInternal(
            Hash256 blockHash,
            Peer expectedPeer,
            IOException failure
    ) throws IOException {

        ensureOpen();
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(expectedPeer, "expectedPeer");
        Objects.requireNonNull(failure, "failure");

        Peer owner = inFlightTracker.peerForBlock(blockHash);
        if (owner != expectedPeer) {
            return false;
        }

        CompletableFuture<DownloadResult> ownedFuture = null;
        DownloadState ownedState = null;

        for (var entry : activeDownloads.entrySet()) {
            ActiveDownload active = entry.getValue();
            if (active.peer() == expectedPeer
                    && active.state().blockHash.equals(blockHash)) {
                ownedFuture = entry.getKey();
                ownedState = active.state();
                break;
            }
        }

        if (ownedFuture == null || ownedState == null) {
            return false; // The shared registry may refer to another chainstate's session.
        }

        activeDownloads.remove(ownedFuture);
        ownedFuture.cancel(true);
        inFlightTracker.remove(expectedPeer, blockHash);
        ownedState.inFlight = false;
        ownedState.awaitingAlternativePeer = true;
        ownedState.failures.add(failure);
        peerStates.computeIfAbsent(expectedPeer, PeerBlockDownloadState::new)
                .stall(System.nanoTime(), Duration.ofSeconds(2));

        /*
         * Keep the current rescue round while at least one READY serviceable peer
         * has not attempted this block yet.  This matters for adaptive stall
         * handling: P1 -> P3 -> P2 must exhaust the distinct peers before an older
         * peer is recycled.
         *
         * Only when every currently READY serviceable peer has already been tried do
         * we roll the state into a new round.  That is the actual frontier=UNASSIGNED
         * escape hatch: older peers become eligible again, while the peer that just
         * stalled remains excluded so the request cannot bounce straight back to it.
         *
         * Capacity is intentionally ignored here.  An unattempted READY peer that is
         * temporarily full still belongs to the current round; assignAvailable() will
         * pick it as soon as one of its slots is released.
         */
        List<Peer> peers = peerManager.readyPeers();
        boolean hasReadyServiceablePeer = false;
        boolean hasUnattemptedReadyServiceablePeer = false;

        for (Peer peer : peers) {
            if (!peer.isReady() || !peerCanServe(peer, ownedState)) {
                continue;
            }

            hasReadyServiceablePeer = true;

            if (!ownedState.attemptedPeers.contains(peer)) {
                hasUnattemptedReadyServiceablePeer = true;
                break;
            }
        }

        if (hasReadyServiceablePeer && !hasUnattemptedReadyServiceablePeer) {
            ownedState.attemptedPeers.clear();
            ownedState.attemptedPeers.add(expectedPeer);
        }

        // Cancelling makes a late completion stale; the dispatcher may still finish
        // normally, but it no longer owns the logical request.
        ownedFuture.cancel(true);

        if (!peers.isEmpty()) {
            assignAvailable(peers);
        }

        return true;
    }

    @Override
    public boolean hasIdlePeerFor(BlockDownloadRequest request, Peer blockingPeer) {
        refreshPeerPolicy();
        return hasIdlePeerForInternal(request, blockingPeer);
    }

    private synchronized boolean hasIdlePeerForInternal(BlockDownloadRequest request, Peer blockingPeer) {
        var peers = peerManager.readyPeers();
        long now = System.nanoTime();
        for (Peer peer : peers) {
            if (peer == blockingPeer || !peer.isReady() || inFlightTracker.count(peer) != 0) continue;
            PeerBlockDownloadState state = peerStates.computeIfAbsent(peer, PeerBlockDownloadState::new);
            if (state.canServe(request.height(), now)
                    && peerPolicy.canProbeWindowEnd(peer, request.blockHash(), request.height())) return true;
        }
        return false;
    }

    @Override
    public Optional<Peer> findWindowStaller(BlockDownloadRequest beyondWindow,
            java.util.function.BiFunction<Peer, BlockDownloadPeerPolicy, Optional<Peer>> probe) {
        refreshPeerPolicy();
        return findWindowStallerInternal(beyondWindow, probe);
    }

    private synchronized Optional<Peer> findWindowStallerInternal(BlockDownloadRequest beyondWindow,
            java.util.function.BiFunction<Peer, BlockDownloadPeerPolicy, Optional<Peer>> probe) {
        var peers = peerManager.readyPeers();
        for (var peer : peers) {
            if (!peer.isReady() || inFlightTracker.count(peer) != 0
                    || !peerPolicy.canProbeWindowEnd(peer, beyondWindow.blockHash(), beyondWindow.height())) continue;
            var staller = Objects.requireNonNull(probe.apply(peer, peerPolicy));
            if (staller.isPresent() && staller.get() != peer) return staller;
        }
        return Optional.empty();
    }

    @Override
    public synchronized void failPeer(
            Peer peer,
            IOException failure
    ) throws IOException {

        ensureOpen();

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                failure,
                "failure"
        );

        failPeerDownloads(
                peer,
                failure
        );
    }

    @Override
    public Optional<Peer> inFlightPeer(
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        return Optional.ofNullable(
                inFlightTracker.peerForBlock(
                        blockHash
                )
        );
    }

    private void refreshPeerPolicy() {
        // Resolving persistent ancestry must not hold the session monitor.
        peerPolicy.refresh(peerManager.readyPeers());
    }
}
