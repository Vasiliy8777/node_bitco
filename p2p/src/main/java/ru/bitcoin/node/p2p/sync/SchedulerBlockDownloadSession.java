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

    private final PeerManager peerManager;
    private final BlockDownloadService blockDownloadService;

    private static final Duration COMPLETION_CHECK_INTERVAL =
            Duration.ofMillis(
                    250
            );

    private final BlockDownloadTimeoutPolicy timeoutPolicy;

    private final BlockDownloadTimeoutEvaluator timeoutEvaluator;

    private final List<DownloadState> states =
            new ArrayList<>();

    private final Set<Hash256> submittedHashes =
            new HashSet<>();

    private final BlockInFlightTracker inFlightTracker =
            new BlockInFlightTracker();

    private final IdentityHashMap<
            Future<DownloadResult>,
            ActiveDownload
            > activeDownloads =
            new IdentityHashMap<>();

    private ExecutorService executor;

    private CompletionService<DownloadResult>
            completionService;

    private int nextIndex;
    private int pendingCount;
    private int nextPeerIndex;

    private boolean closed;

    public SchedulerBlockDownloadSession(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {

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

        this.timeoutEvaluator =
                new BlockDownloadTimeoutEvaluator(
                        inFlightTracker,
                        timeoutPolicy
                );
    }

    @Override
    public synchronized void submit(
            List<Hash256> blockHashes
    ) throws IOException {

        ensureOpen();

        Objects.requireNonNull(
                blockHashes,
                "blockHashes"
        );

        Set<Hash256> batchHashes =
                new HashSet<>();

        for (Hash256 blockHash : blockHashes) {

            Objects.requireNonNull(
                    blockHash,
                    "blockHashes must not contain null"
            );

            if (!batchHashes.add(
                    blockHash
            )) {
                throw new IllegalArgumentException(
                        "Duplicate block hash in submitted batch: "
                                + blockHash.toDisplayHex()
                );
            }

            if (submittedHashes.contains(
                    blockHash
            )) {
                throw new IllegalArgumentException(
                        "Block hash was already submitted to this session: "
                                + blockHash.toDisplayHex()
                );
            }
        }

        if (blockHashes.isEmpty()) {
            return;
        }

        List<Peer> peers =
                peerManager.readyPeers();

        /*
         * Validate all integer arithmetic before changing
         * session state.
         */
        int prospectiveNextIndex =
                Math.addExact(
                        nextIndex,
                        blockHashes.size()
                );

        int prospectivePendingCount =
                Math.addExact(
                        pendingCount,
                        blockHashes.size()
                );

        int index =
                nextIndex;

        for (Hash256 blockHash : blockHashes) {

            states.add(
                    new DownloadState(
                            index,
                            blockHash
                    )
            );

            submittedHashes.add(
                    blockHash
            );

            index =
                    Math.incrementExact(
                            index
                    );
        }

        nextIndex =
                prospectiveNextIndex;

        pendingCount =
                prospectivePendingCount;

        if (!peers.isEmpty()) {

            ensureExecutor(
                    peers.size()
            );

            assignAvailable(
                    peers
            );
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

        Future<DownloadResult> future;

        boolean waitForReadyPeer = false;

        synchronized (this) {

            ensureOpen();

            if (pendingCount == 0) {
                throw new IllegalStateException(
                        "No pending block downloads"
                );
            }

            List<Peer> peers =
                    peerManager.readyPeers();

            if (!peers.isEmpty()) {

                ensureExecutor(
                        peers.size()
                );

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

                } else {

                    /*
                     * READY peers still genuinely exist.
                     *
                     * assignAvailable() has already had an opportunity to assign the
                     * incomplete work. If nothing is active now, the available peers
                     * have been exhausted for this block (for example they returned
                     * NOTFOUND).
                     *
                     * Preserve terminal failure for that case.
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
                    completionService.poll(
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
                    future.get();

        } catch (CancellationException exception) {

            throw new IOException(
                    "Registered block download task was unexpectedly cancelled",
                    exception
            );

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IOException(
                    "Block download interrupted",
                    exception
            );

        } catch (ExecutionException exception) {

            throw new IOException(
                    "Unexpected block download task failure",
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

                state.failures.add(
                        result.failure()
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

            if (state.completed) {
                throw new IllegalStateException(
                        "Block download completed more than once: "
                                + state.blockHash.toDisplayHex()
                );
            }

            state.completed =
                    true;

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

            return Optional.of(
                    new CompletedBlockDownload(
                            state.index,
                            state.blockHash,
                            block
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

    @Override
    public void close() {

        List<Future<DownloadResult>> futures;
        ExecutorService executorToClose;

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

            activeDownloads.clear();

            executorToClose =
                    executor;
        }

        for (Future<DownloadResult> future :
                futures) {

            future.cancel(
                    true
            );
        }

        if (executorToClose != null) {
            executorToClose.shutdownNow();
        }
    }

    private void ensureExecutor(
            int readyPeerCount
    ) {

        if (executor != null) {
            return;
        }

        int threadCount =
                Math.multiplyExact(
                        readyPeerCount,
                        BlockInFlightTracker
                                .DEFAULT_MAX_BLOCKS_PER_PEER
                );

        executor =
                Executors.newFixedThreadPool(
                        threadCount
                );

        completionService =
                new ExecutorCompletionService<>(
                        executor
                );
    }

    private void assignAvailable(
            List<Peer> peers
    ) {

        if (completionService == null) {
            return;
        }

        boolean assigned;

        do {

            assigned = false;

            for (int offset = 0;
                 offset < peers.size();
                 offset++) {

                int peerIndex =
                        (nextPeerIndex + offset)
                                % peers.size();

                Peer peer =
                        peers.get(
                                peerIndex
                        );

                if (!peer.isReady()) {
                    continue;
                }

                if (!inFlightTracker.canRegister(
                        peer
                )) {
                    continue;
                }

                DownloadState state =
                        findAssignableState(
                                peer
                        );

                if (state == null) {
                    continue;
                }

                state.inFlight =
                        true;

                state.attemptedPeers.add(
                        peer
                );

                inFlightTracker.register(
                        peer,
                        state.blockHash
                );

                Future<DownloadResult> future;

                try {

                    future =
                            completionService.submit(
                                    () -> download(
                                            peer,
                                            state
                                    )
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

                nextPeerIndex =
                        (peerIndex + 1)
                                % peers.size();

                assigned = true;

                break;
            }

        } while (assigned);
    }

    private DownloadState findAssignableState(
            Peer peer
    ) {

        for (DownloadState state :
                states) {

            if (state.completed
                    || state.inFlight) {
                continue;
            }

            if (state.attemptedPeers.contains(
                    peer
            )) {
                continue;
            }

            return state;
        }

        return null;
    }

    private DownloadState firstIncomplete() {

        for (DownloadState state :
                states) {

            if (!state.completed) {
                return state;
            }
        }

        return null;
    }

    private DownloadResult download(
            Peer peer,
            DownloadState state
    ) {

        try {

            Block block =
                    blockDownloadService.download(
                            peer,
                            state.blockHash
                    );

            return DownloadResult.success(
                    peer,
                    state,
                    block
            );

        } catch (IOException exception) {

            return DownloadResult.failure(
                    peer,
                    state,
                    exception
            );
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

    private static final class DownloadState {

        private final int index;
        private final Hash256 blockHash;

        private final Set<Peer> attemptedPeers =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        private final List<IOException> failures =
                new ArrayList<>();

        private boolean inFlight;
        private boolean completed;

        private DownloadState(
                int index,
                Hash256 blockHash
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

    private List<Future<DownloadResult>>
    activeDownloadsForPeer(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        List<Future<DownloadResult>> futures =
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

        List<Future<DownloadResult>> futures =
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
        Set<Hash256> removedBlocks =
                inFlightTracker.removeAll(
                        peer
                );

        int released = 0;

        for (Future<DownloadResult> future :
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

            IOException failure =
                    new IOException(
                            "Peer block download timed out after "
                                    + evaluation.downloadingAge()
                                    + " (timeout "
                                    + evaluation.timeout()
                                    + ")"
                    );

            released =
                    Math.addExact(
                            released,
                            failPeerDownloads(
                                    peer,
                                    failure
                            )
                    );

            try {

                peer.close();

            } catch (IOException closeException) {

                failure.addSuppressed(
                        closeException
                );
            }
        }

        return released;
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
    public synchronized Optional<Peer> inFlightPeer(
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
}