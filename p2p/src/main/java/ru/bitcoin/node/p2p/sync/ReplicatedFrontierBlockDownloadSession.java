package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.LimitedHistoryPeerPolicy;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnectionRole;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Replicated 128-block IBD frontier. Network I/O is never performed under this session monitor. */
final class ReplicatedFrontierBlockDownloadSession implements BlockDownloadSession {
    private static final System.Logger log = System.getLogger(ReplicatedFrontierBlockDownloadSession.class.getName());
    static final int CACHE_BLOCKS_PER_PEER = 128;
    static final int MAX_CACHE_PEERS = 8;
    static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(2);

    private final PeerManager peerManager;
    private final BlockDownloadService downloadService;
    private final BlockDownloadTimeoutPolicy timeoutPolicy;
    private final Consumer<BlockDownloadSession> closeListener;
    private final LinkedHashMap<Hash256, State> states = new LinkedHashMap<>();
    private final Set<Hash256> submitted = new HashSet<>();
    private final BlockingQueue<Completion> completions = new LinkedBlockingQueue<>();
    private final Deque<CompletedBlockDownload> external = new ArrayDeque<>();
    private final IdentityHashMap<Peer, Long> cooldownUntil = new IdentityHashMap<>();
    private int nextIndex;
    private int pending;
    private boolean closed;

    ReplicatedFrontierBlockDownloadSession(PeerManager peerManager,
                                           BlockDownloadService downloadService,
                                           BlockDownloadTimeoutPolicy timeoutPolicy,
                                           Consumer<BlockDownloadSession> closeListener) {
        this.peerManager = Objects.requireNonNull(peerManager);
        this.downloadService = Objects.requireNonNull(downloadService);
        this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy);
        this.closeListener = Objects.requireNonNull(closeListener);
    }

    @Override public void submit(List<Hash256> hashes) throws IOException {
        submitRequests(hashes.stream().map(h -> new BlockDownloadRequest(h, 0)).toList());
    }

    @Override public void submitRequests(List<BlockDownloadRequest> requests) throws IOException {
        Objects.requireNonNull(requests, "requests");
        synchronized (this) {
            ensureOpen();
            if (states.size() + requests.size() > CACHE_BLOCKS_PER_PEER)
                throw new IOException("Replicated frontier exceeds " + CACHE_BLOCKS_PER_PEER
                        + " unfinished logical blocks: active=" + states.size() + ", submitted=" + requests.size());
            for (BlockDownloadRequest request : requests) {
                if (!submitted.add(request.blockHash()))
                    throw new IllegalArgumentException("Block hash was already submitted: " + request.blockHash().toDisplayHex());
                states.put(request.blockHash(), new State(nextIndex++, request.blockHash(), request.height()));
                pending++;
            }
        }
        synchronizeReplicas();
    }

    @Override public CompletedBlockDownload awaitCompleted() throws IOException {
        while (true) {
            Optional<CompletedBlockDownload> r = pollCompleted(Duration.ofMillis(250));
            if (r.isPresent()) return r.get();
        }
    }

    @Override public Optional<CompletedBlockDownload> pollCompleted(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout);
        synchronized (this) {
            ensureOpen();
            CompletedBlockDownload e = external.pollFirst();
            if (e != null) { pending--; return Optional.of(e); }
            if (pending == 0) throw new IllegalStateException("No pending block downloads");
        }

        expireTimedOutAttempts();
        synchronizeReplicas();
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        while (true) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            Completion completion;
            try {
                completion = timeout.isZero() ? completions.poll() : completions.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Block download interrupted", e);
            }
            if (completion == null) return Optional.empty();
            Optional<CompletedBlockDownload> useful = consumeCompletion(completion);
            if (useful.isPresent()) return useful;
            // stale/cancelled/failed replicas are queue noise: keep draining instead of reporting a false idle interval.
            if (!timeout.isZero() && System.nanoTime() >= deadline) return Optional.empty();
        }
    }

    private Optional<CompletedBlockDownload> consumeCompletion(Completion completion) {
        State state;
        Attempt attempt;
        synchronized (this) {
            state = states.get(completion.hash);
            if (state == null || state.completed) return Optional.empty();
            attempt = state.attempts.get(completion.peer);
            if (attempt == null || attempt.generation != completion.generation || attempt.future != completion.future)
                return Optional.empty();
            state.attempts.remove(completion.peer);
        }

        Block block;
        try {
            block = completion.future.join();
        } catch (CancellationException | CompletionException failure) {
            coolPeer(completion.peer);
            synchronizeReplicas();
            return Optional.empty();
        }
        if (block == null || !completion.hash.equals(block.hash())) {
            coolPeer(completion.peer);
            synchronizeReplicas();
            return Optional.empty();
        }

        List<CompletableFuture<Block>> siblings;
        CompletedBlockDownload result;
        synchronized (this) {
            state = states.get(completion.hash);
            if (state == null || state.completed) return Optional.empty();
            state.completed = true;
            states.remove(state.hash);
            pending--;
            siblings = state.attempts.values().stream().map(a -> a.future).filter(Objects::nonNull).toList();
            state.attempts.clear();
            result = new CompletedBlockDownload(state.index, state.hash, block, completion.peer);
        }
        siblings.forEach(f -> f.cancel(false));
        synchronizeReplicas();
        return Optional.of(result);
    }

    /** Build reservations under lock, perform the actual batched peer.send outside it. */
    private void synchronizeReplicas() {
        List<BatchPlan> plans = planReplicaBatches();
        for (BatchPlan plan : plans) executeBatch(plan);
    }

    private List<BatchPlan> planReplicaBatches() {
        List<Peer> peers = peerManager.readyPeers().stream()
                .filter(Peer::isReady)
                .filter(p -> { try { return peerManager.roleOf(p) == PeerConnectionRole.FULL_RELAY; }
                catch (RuntimeException ignored) { return false; } })
                .limit(MAX_CACHE_PEERS).toList();
        long now = System.nanoTime();
        List<BatchPlan> plans = new ArrayList<>();
        synchronized (this) {
            if (closed) return List.of();
            for (Peer peer : peers) {
                if (cooldownUntil.getOrDefault(peer, 0L) > now) continue;
                List<Hash256> hashes = new ArrayList<>();
                for (State state : states.values()) {
                    if (state.completed || state.attempts.containsKey(peer)) continue;
                    if (!LimitedHistoryPeerPolicy.canServeBlockHeight(peer.remoteVersion(), state.height)) continue;
                    long generation = ++state.nextGeneration;
                    state.attempts.put(peer, new Attempt(generation, now, null)); // reservation prevents duplicate planning
                    hashes.add(state.hash);
                    if (hashes.size() == CACHE_BLOCKS_PER_PEER) break;
                }
                if (!hashes.isEmpty()) plans.add(new BatchPlan(peer, List.copyOf(hashes)));
            }
        }
        return plans;
    }

    private void executeBatch(BatchPlan plan) {
        Map<Hash256, CompletableFuture<Block>> futures;
        try {
            log.log(System.Logger.Level.INFO, "IBD replicated GETDATA batch: peer={0}, blocks={1}",
                    plan.peer.remoteAddress(), plan.hashes.size());
            futures = downloadService.downloadBatchAsync(plan.peer, plan.hashes);
        } catch (RuntimeException failure) {
            releaseBatchReservations(plan, true);
            return;
        }
        for (Hash256 hash : plan.hashes) {
            CompletableFuture<Block> future = futures.get(hash);
            if (future == null) {
                synchronized (this) {
                    State state = states.get(hash);
                    if (state != null) {
                        Attempt a = state.attempts.get(plan.peer);
                        if (a != null && a.future == null) state.attempts.remove(plan.peer);
                    }
                }
                coolPeer(plan.peer);
                continue;
            }
            long generation;
            boolean keep;
            synchronized (this) {
                State state = states.get(hash);
                Attempt reserved = state == null ? null : state.attempts.get(plan.peer);
                keep = !closed && state != null && !state.completed && reserved != null && reserved.future == null;
                if (keep) {
                    generation = reserved.generation;
                    state.attempts.put(plan.peer, new Attempt(generation, reserved.startedNanos, future));
                } else generation = -1;
            }
            if (!keep) future.cancel(false);
            else {
                long g = generation;
                future.whenComplete((b, f) -> completions.offer(new Completion(plan.peer, hash, g, future)));
            }
        }
    }

    private void releaseBatchReservations(BatchPlan plan, boolean cool) {
        synchronized (this) {
            for (Hash256 hash : plan.hashes) {
                State state = states.get(hash);
                if (state != null) {
                    Attempt a = state.attempts.get(plan.peer);
                    if (a != null && a.future == null) state.attempts.remove(plan.peer);
                }
            }
        }
        if (cool) coolPeer(plan.peer);
    }

    private void expireTimedOutAttempts() {
        long now = System.nanoTime();
        List<CompletableFuture<Block>> cancel = new ArrayList<>();
        Set<Peer> timedOutPeers = Collections.newSetFromMap(new IdentityHashMap<>());
        int peerCount = Math.max(1, currentCachePeerCount());
        long timeoutNanos = timeoutPolicy.timeout(Math.max(0, peerCount - 1)).toNanos();
        synchronized (this) {
            for (State state : states.values()) {
                Iterator<Map.Entry<Peer, Attempt>> it = state.attempts.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Peer, Attempt> e = it.next();
                    Attempt a = e.getValue();
                    if (a.future != null && now - a.startedNanos > timeoutNanos) {
                        it.remove();
                        cancel.add(a.future);
                        timedOutPeers.add(e.getKey());
                    }
                }
            }
        }
        cancel.forEach(f -> f.cancel(false));
        if (!cancel.isEmpty())
            log.log(System.Logger.Level.WARNING, "IBD replicated timeout: released={0}, peers={1}", cancel.size(), timedOutPeers.size());
        timedOutPeers.forEach(this::coolPeer);
    }

    private int currentCachePeerCount() {
        return (int) peerManager.readyPeers().stream().filter(Peer::isReady).filter(p -> {
            try { return peerManager.roleOf(p) == PeerConnectionRole.FULL_RELAY; }
            catch (RuntimeException ignored) { return false; }
        }).limit(MAX_CACHE_PEERS).count();
    }

    private void coolPeer(Peer peer) {
        synchronized (this) { cooldownUntil.put(peer, System.nanoTime() + FAILURE_COOLDOWN.toNanos()); }
    }

    @Override public synchronized int pendingCount() { return pending; }
    @Override public synchronized Optional<Peer> inFlightPeer(Hash256 hash) {
        State s = states.get(hash);
        if (s == null) return Optional.empty();
        return s.attempts.entrySet().stream().filter(e -> e.getValue().future != null && e.getKey().isReady())
                .map(Map.Entry::getKey).findFirst();
    }

    @Override public void failPeer(Peer peer, IOException failure) {
        List<CompletableFuture<Block>> cancel = detachPeer(peer);
        cancel.forEach(f -> f.cancel(false));
        coolPeer(peer);
        synchronizeReplicas();
    }

    @Override public boolean retryBlock(Hash256 hash, Peer expectedPeer, IOException failure) {
        CompletableFuture<Block> cancel = null;
        synchronized (this) {
            State s = states.get(hash);
            if (s == null) return false;
            Attempt a = s.attempts.remove(expectedPeer);
            if (a == null) return false;
            cancel = a.future;
        }
        if (cancel != null) cancel.cancel(false);
        coolPeer(expectedPeer);
        synchronizeReplicas();
        return true;
    }

    private List<CompletableFuture<Block>> detachPeer(Peer peer) {
        List<CompletableFuture<Block>> cancel = new ArrayList<>();
        synchronized (this) {
            for (State s : states.values()) {
                Attempt a = s.attempts.remove(peer);
                if (a != null && a.future != null) cancel.add(a.future);
            }
        }
        return cancel;
    }

    synchronized boolean hasPendingBlock(Hash256 hash) { return states.containsKey(hash); }
    synchronized boolean hasSubmittedBlock(Hash256 hash) { return submitted.contains(hash); }

    boolean acceptExternalBlock(Peer sourcePeer, Block block) {
        List<CompletableFuture<Block>> cancel;
        synchronized (this) {
            State s = states.get(block.hash());
            if (s == null || s.completed) return false;
            s.completed = true;
            states.remove(s.hash);
            cancel = s.attempts.values().stream().map(a -> a.future).filter(Objects::nonNull).toList();
            s.attempts.clear();
            external.addLast(new CompletedBlockDownload(s.index, s.hash, block, sourcePeer));
        }
        cancel.forEach(f -> f.cancel(false));
        return true;
    }

    @Override public void close() {
        List<CompletableFuture<Block>> futures = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (State s : states.values()) for (Attempt a : s.attempts.values()) if (a.future != null) futures.add(a.future);
            states.clear();
        }
        futures.forEach(f -> f.cancel(false));
        closeListener.accept(this);
    }

    private synchronized void ensureOpen() throws IOException { if (closed) throw new IOException("Block download session is closed"); }

    private static final class State {
        final int index; final Hash256 hash; final long height;
        final IdentityHashMap<Peer, Attempt> attempts = new IdentityHashMap<>();
        long nextGeneration; boolean completed;
        State(int index, Hash256 hash, long height) { this.index=index; this.hash=hash; this.height=height; }
    }
    private record Attempt(long generation, long startedNanos, CompletableFuture<Block> future) {}
    private record BatchPlan(Peer peer, List<Hash256> hashes) {}
    private record Completion(Peer peer, Hash256 hash, long generation, CompletableFuture<Block> future) {}
}
