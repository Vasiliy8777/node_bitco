package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnectionRole;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * IBD frontier session with a replicated 128-block cache on every FULL_RELAY
 * outbound peer. The logical frontier is unique, but every cache peer requests
 * the same hashes. The first valid copy wins; sibling requests are cancelled.
 *
 * <p>The session deliberately keeps logical state independent from a Peer
 * instance. A disconnected peer therefore loses only its network futures; the
 * 128-block frontier remains live and a replacement peer immediately joins the
 * current rotation instead of starting a new window.</p>
 */
final class ReplicatedFrontierBlockDownloadSession implements BlockDownloadSession {
    static final int CACHE_BLOCKS_PER_PEER = 128;
    static final int MAX_CACHE_PEERS = 8;

    private final PeerManager peerManager;
    private final BlockDownloadService downloadService;
    private final Consumer<BlockDownloadSession> closeListener;
    private final LinkedHashMap<Hash256, State> states = new LinkedHashMap<>();
    private final Set<Hash256> submitted = new HashSet<>();
    private final IdentityHashMap<CompletableFuture<Result>, Attempt> active = new IdentityHashMap<>();
    private final BlockingQueue<CompletableFuture<Result>> completions = new LinkedBlockingQueue<>();
    private final Deque<CompletedBlockDownload> external = new ArrayDeque<>();
    private int nextIndex;
    private int pending;
    private boolean closed;

    ReplicatedFrontierBlockDownloadSession(PeerManager peerManager,
                                           BlockDownloadService downloadService,
                                           Consumer<BlockDownloadSession> closeListener) {
        this.peerManager = Objects.requireNonNull(peerManager);
        this.downloadService = Objects.requireNonNull(downloadService);
        this.closeListener = Objects.requireNonNull(closeListener);
    }

    @Override
    public synchronized void submit(List<Hash256> hashes) throws IOException {
        submitRequests(hashes.stream().map(h -> new BlockDownloadRequest(h, 0)).toList());
    }

    @Override
    public synchronized void submitRequests(List<BlockDownloadRequest> requests) throws IOException {
        ensureOpen();
        Objects.requireNonNull(requests, "requests");
        if (requests.isEmpty()) return;

        /*
         * Hard safety boundary for the replicated frontier.  The coordinator
         * advances this window in 64-block rotations only after ordered chain
         * progress has consumed half of the previous 128-block window.  Do not
         * let any caller accidentally turn replication into an unbounded
         * duplicate download pipeline.
         */
        if (states.size() + requests.size() > CACHE_BLOCKS_PER_PEER) {
            throw new IOException(
                    "Replicated frontier exceeds " + CACHE_BLOCKS_PER_PEER
                            + " unfinished logical blocks: active=" + states.size()
                            + ", submitted=" + requests.size());
        }

        for (BlockDownloadRequest request : requests) {
            if (!submitted.add(request.blockHash()))
                throw new IllegalArgumentException("Block hash was already submitted: " + request.blockHash().toDisplayHex());
            states.put(request.blockHash(), new State(nextIndex++, request.blockHash(), request.height()));
            pending++;
        }
        synchronizeReplicas();
    }

    @Override
    public CompletedBlockDownload awaitCompleted() throws IOException {
        while (true) {
            Optional<CompletedBlockDownload> r = pollCompleted(Duration.ofMillis(250));
            if (r.isPresent()) return r.get();
        }
    }

    @Override
    public Optional<CompletedBlockDownload> pollCompleted(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout);
        synchronized (this) {
            ensureOpen();
            CompletedBlockDownload e = external.pollFirst();
            if (e != null) {
                pending--;
                return Optional.of(e);
            }
            synchronizeReplicas();
            if (pending == 0) throw new IllegalStateException("No pending block downloads");
        }

        CompletableFuture<Result> future;
        try {
            future = completions.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Block download interrupted", e);
        }
        if (future == null) {
            synchronized (this) {
                synchronizeReplicas();
            }
            return Optional.empty();
        }

        synchronized (this) {
            Attempt attempt = active.remove(future);
            if (attempt == null) return Optional.empty();
            State state = attempt.state;
            state.attempts.remove(attempt.peer);
            Result result;
            try {
                result = future.join();
            } catch (CancellationException e) {
                synchronizeReplicas();
                return Optional.empty();
            } catch (CompletionException e) {
                synchronizeReplicas();
                return Optional.empty();
            }

            if (state.completed || !states.containsKey(state.hash)) return Optional.empty();
            if (result.failure != null) {
                synchronizeReplicas();
                return Optional.empty();
            }
            if (!state.hash.equals(result.block.hash())) {
                synchronizeReplicas();
                return Optional.empty();
            }

            state.completed = true;
            states.remove(state.hash);
            pending--;
            cancelSiblings(state, future);
            synchronizeReplicas();
            return Optional.of(new CompletedBlockDownload(state.index, state.hash, result.block, result.peer));
        }
    }

    /**
     * Keep every current FULL_RELAY peer on exactly the same unfinished frontier.
     */
    private void synchronizeReplicas() {
        if (closed) return;
        List<Peer> cachePeers = peerManager.readyPeers().stream()
                .filter(Peer::isReady)
                .filter(p -> {
                    try {
                        return peerManager.roleOf(p) == PeerConnectionRole.FULL_RELAY;
                    } catch (RuntimeException ignored) {
                        return false;
                    }
                })
                .limit(MAX_CACHE_PEERS)
                .toList();

        for (State state : states.values()) {
            if (state.completed) continue;
            for (Peer peer : cachePeers) {
                if (state.attempts.containsKey(peer)) continue;
                if (!ru.bitcoin.node.p2p.LimitedHistoryPeerPolicy.canServeBlockHeight(peer.remoteVersion(), state.height))
                    continue;
                CompletableFuture<Result> f = downloadService.downloadAsync(peer, state.hash)
                        .handle((block, failure) -> failure == null
                                ? new Result(peer, block, null)
                                : new Result(peer, null, unwrap(failure)));
                state.attempts.put(peer, f);
                Attempt attempt = new Attempt(peer, state);
                active.put(f, attempt);
                f.whenComplete((ignored, ignoredFailure) -> completions.offer(f));
            }
        }
    }

    private void cancelSiblings(State state, CompletableFuture<Result> winner) {
        for (CompletableFuture<Result> f : new ArrayList<>(state.attempts.values())) {
            if (f == winner) continue;
            active.remove(f);
            f.cancel(false);
        }
        state.attempts.clear();
    }

    @Override
    public synchronized int pendingCount() {
        return pending;
    }

    @Override
    public synchronized Optional<Peer> inFlightPeer(Hash256 hash) {
        State s = states.get(hash);
        if (s == null || s.attempts.isEmpty()) return Optional.empty();
        return s.attempts.keySet().stream().filter(Peer::isReady).findFirst();
    }

    @Override
    public synchronized void failPeer(Peer peer, IOException failure) {
        for (State s : states.values()) {
            CompletableFuture<Result> f = s.attempts.remove(peer);
            if (f != null) {
                active.remove(f);
                f.cancel(false);
            }
        }
        synchronizeReplicas();
    }

    @Override
    public synchronized boolean retryBlock(Hash256 hash, Peer expectedPeer, IOException failure) {
        State s = states.get(hash);
        if (s == null) return false;
        CompletableFuture<Result> f = s.attempts.remove(expectedPeer);
        if (f == null) return false;
        active.remove(f);
        f.cancel(false);
        synchronizeReplicas();
        return true;
    }

    synchronized boolean hasPendingBlock(Hash256 hash) {
        return states.containsKey(hash);
    }

    synchronized boolean hasSubmittedBlock(Hash256 hash) {
        return submitted.contains(hash);
    }

    synchronized boolean acceptExternalBlock(Peer sourcePeer, Block block) {
        State s = states.get(block.hash());
        if (s == null || s.completed) return false;
        s.completed = true;
        states.remove(s.hash);
        cancelSiblings(s, null);
        external.addLast(new CompletedBlockDownload(s.index, s.hash, block, sourcePeer));
        return true;
    }

    @Override
    public void close() {
        List<CompletableFuture<Result>> futures;
        synchronized (this) {
            if (closed) return;
            closed = true;
            futures = new ArrayList<>(active.keySet());
            active.clear();
            states.clear();
        }
        futures.forEach(f -> f.cancel(false));
        closeListener.accept(this);
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Block download session is closed");
    }

    private static IOException unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null)
            c = c.getCause();
        return c instanceof IOException io ? io : new IOException("Replicated block download failed", c);
    }

    private static final class State {
        final int index;
        final Hash256 hash;
        final long height;
        final IdentityHashMap<Peer, CompletableFuture<Result>> attempts = new IdentityHashMap<>();
        boolean completed;

        State(int index, Hash256 hash, long height) {
            this.index = index;
            this.hash = hash;
            this.height = height;
        }
    }

    private record Attempt(Peer peer, State state) {
    }

    private record Result(Peer peer, Block block, IOException failure) {
    }
}
