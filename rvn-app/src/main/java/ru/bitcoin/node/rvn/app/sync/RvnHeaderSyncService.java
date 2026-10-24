package ru.bitcoin.node.rvn.app.sync;

import org.springframework.context.SmartLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChain;
import ru.bitcoin.node.rvn.app.chain.RvnNativeBlockReplay;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.p2p.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Supervised headers and optional candidate bodies; never advertises a UTXO height or enables mining. */
public final class RvnHeaderSyncService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RvnHeaderSyncService.class);
    private final RvnMainnetHeaderChain chain;
    private final List<String> peers;
    private final int port, timeoutMs;
    private final long responseTimeoutMs, pollMs;
    private final RvnBlockDownloader blocks;
    private final RvnNativeBlockReplay replay;
    private final RvnSeedResolver resolver;
    private final Map<String, Long> addressCursor = new HashMap<>();
    private final ThreadPoolExecutor connector = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon(true).name("rvn-connect").factory());
    private volatile boolean running;
    private volatile RvnPeerSession active;
    private volatile long lastHeaderResponseNanos;
    private record MinedRelay(RvnHash256 id, ru.bitcoin.node.rvn.chain.RvnBlock block, CompletableFuture<Void> done) {}
    private final ArrayBlockingQueue<MinedRelay> minedRelay = new ArrayBlockingQueue<>(1);
    private Thread worker;

    public RvnHeaderSyncService(RvnMainnetHeaderChain chain, List<String> peers, int port,
                                int timeoutMs, long responseTimeoutMs, long pollMs) {
        this(chain, peers, port, timeoutMs, responseTimeoutMs, pollMs, null);
    }

    public RvnHeaderSyncService(RvnMainnetHeaderChain chain, List<String> peers, int port,
                                int timeoutMs, long responseTimeoutMs, long pollMs, RvnBlockDownloader blocks) {
        this(chain, peers, port, timeoutMs, responseTimeoutMs, pollMs, blocks, null);
    }

    public RvnHeaderSyncService(RvnMainnetHeaderChain chain, List<String> peers, int port,
                                int timeoutMs, long responseTimeoutMs, long pollMs, RvnBlockDownloader blocks,
                                RvnNativeBlockReplay replay) {
        this(chain, peers, port, timeoutMs, responseTimeoutMs, pollMs, blocks, replay, new RvnSeedResolver(""));
    }

    public RvnHeaderSyncService(RvnMainnetHeaderChain chain, List<String> peers, int port,
                                int timeoutMs, long responseTimeoutMs, long pollMs, RvnBlockDownloader blocks,
                                RvnNativeBlockReplay replay, RvnSeedResolver resolver) {
        this.chain = Objects.requireNonNull(chain);
        this.peers = List.copyOf(peers);
        if (peers.isEmpty() || peers.size() > 64 || peers.stream().anyMatch(s -> s == null || s.isBlank())
                || port < 1 || port > 65535 || timeoutMs < 100 || timeoutMs > 60000
                || responseTimeoutMs < 100 || responseTimeoutMs > 120000 || pollMs < 100 || pollMs > 60000)
            throw new IllegalArgumentException("Invalid RVN sync settings");
        this.port = port; this.timeoutMs = timeoutMs; this.responseTimeoutMs = responseTimeoutMs; this.pollMs = pollMs;
        this.blocks = blocks;
        if (replay != null && blocks == null) throw new IllegalArgumentException("RVN native replay requires block download");
        this.replay = replay;
        this.resolver = Objects.requireNonNull(resolver);
    }

    @Override public synchronized void start() {
        if (running) return;
        if (connector.isShutdown()) throw new IllegalStateException("RVN sync service already stopped");
        running = true;
        worker = Thread.ofPlatform().name("rvn-header-sync").unstarted(this::run);
        worker.start();
    }

    private void run() {
        int peerIndex = 0; long backoff = 1000;
        try {
            // Resume cached bodies before DNS/network access after a restart.
            while (running && replayBodies(128) > 0) { }
            while (running) {
                String peer = peers.get(Math.floorMod(peerIndex++, peers.size()));
                try (RvnPeerSession session = connectBounded(peer)) {
                    active = session;
                    lastHeaderResponseNanos = 0;
                    if (!running) break;
                    log.info("RVN header peer connected: {}:{}; local header height={}", peer, port, chain.best().height());
                    RvnHash256 cursor = chain.best().id();
                    long cursorTime = chain.best().timestamp();
                    int stagnant = 0, empty = 0;
                    while (running) {
                        drainMinedRelays(session);
                        var headers = RvnHeadersClient.request(session, 70028, chain.locators(cursor),
                                new byte[32], RvnActivation.MAINNET_KAWPOW_TIME, responseTimeoutMs);
                        lastHeaderResponseNanos = System.nanoTime();
                        if (headers.isEmpty()) {
                            if (downloadBodies(session, 128) > 0) { empty = 0; continue; }
                            // Keep this peer's verified cursor even when it is on a shorter
                            // branch; resetting to best would repeatedly download old batches.
                            if (++empty >= 3 && cursorTime < System.currentTimeMillis() / 1000 - 7200)
                                throw new IOException("RVN peer has no recent headers; trying another peer");
                            backoff = 1000;
                            Thread.sleep(pollMs);
                            continue;
                        }
                        var result = chain.accept(headers);
                        empty = 0;
                        if (cursor.equals(result.lastId())) {
                            if (++stagnant >= 2) throw new IOException("RVN peer is not advancing the header cursor");
                        } else stagnant = 0;
                        cursor = result.lastId(); backoff = 1000;
                        cursorTime = headers.getLast().timestamp();
                        log.info("RVN headers: received={}, added={}, bestHeight={}, bestId={}",
                                headers.size(), result.added(), result.best().height(), result.best().id());
                        downloadBodies(session, 128);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                } catch (IOException | SecurityException | IllegalArgumentException | RejectedExecutionException e) {
                    active = null; lastHeaderResponseNanos = 0;
                    // Surface a poisoned local store as a fatal local error, not a peer retry.
                    if (running) chain.best();
                    if (running) log.warn("RVN header peer {}:{} failed: {}", peer, port, e.getMessage());
                    if (!running) break;
                    try { Thread.sleep(backoff); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    backoff = Math.min(10000, backoff * 2);
                } finally { active = null; lastHeaderResponseNanos = 0; failMinedRelays(); }
            }
        } catch (RuntimeException fatal) {
            if (running && !Thread.currentThread().isInterrupted())
                log.error("RVN header synchronization stopped after a local failure", fatal);
        } finally { running = false; connector.shutdownNow(); resolver.close(); failMinedRelays(); }
    }

    private int downloadBodies(RvnPeerSession session, int limit) throws IOException, InterruptedException {
        if (blocks == null) return 0;
        int replayed = replayBodies(limit);
        int added = blocks.downloadBatch(session, limit);
        if (added > 0) log.info("RVN candidate block bodies: added={}, downloadedHeight={}; UTXO validation pending",
                added, blocks.downloadedHeight());
        return added + replayed + replayBodies(limit);
    }

    private int replayBodies(int limit) {
        if (replay == null) return 0;
        try {
            var progress = replay.advance(limit);
            if (progress.connected() + progress.disconnected() > 0)
                log.info("RVN supported-subset replay: connected={}, disconnected={}, checkedHeight={}; complete historical validation pending",
                        progress.connected(), progress.disconnected(), progress.tip().height());
            return progress.connected() + progress.disconnected();
        } catch (IOException | RuntimeException e) {
            // Stored-data replay failures are local validation failures, not peer retries.
            throw new IllegalStateException("RVN stored native-block replay failed", e);
        }
    }

    /** DNS/handshake run on a bounded daemon executor so shutdown cannot hang in platform DNS. */
    private RvnPeerSession connectBounded(String host) throws IOException, InterruptedException {
        connector.purge();
        var abandoned = new AtomicBoolean();
        var pending = new AtomicReference<RvnPeerSession>();
        Future<RvnPeerSession> connection = connector.submit(() -> {
            var addresses = resolver.resolve(host);
            long index = addressCursor.getOrDefault(host, 0L);
            addressCursor.put(host, index + 1);
            String selected = addresses.get((int) Math.floorMod(index, addresses.size()));
            RvnPeerSession session;
            try { session = RvnPeerSession.connect(RvnNetwork.MAINNET, selected, port, timeoutMs, 256); }
            catch (IOException e) { throw new IOException("RVN peer " + host + " via " + selected + ":" + port + ": " + e, e); }
            pending.set(session);
            if (abandoned.get() || !running) {
                session.close(); throw new IOException("RVN connection cancelled");
            }
            return session;
        });
        try {
            var session = connection.get(timeoutMs * 2L + 8000, TimeUnit.MILLISECONDS);
            pending.set(null);
            return session;
        } catch (ExecutionException e) {
            throw new IOException("RVN connection failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("RVN DNS/connect/handshake deadline exceeded", e);
        } finally {
            abandoned.set(true); connection.cancel(true);
            var leftover = pending.getAndSet(null);
            if (leftover != null) leftover.close();
        }
    }

    @Override public void stop() {
        Thread stopping;
        synchronized (this) { running = false; stopping = worker; }
        var session = active;
        if (session != null) session.close();
        connector.shutdownNow();
        resolver.close();
        failMinedRelays();
        if (stopping != null && stopping != Thread.currentThread()) {
            stopping.interrupt();
            try { stopping.join(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    @Override public boolean isRunning() { return running; }

    /** Handshake alone does not establish a responsive synchronization peer. */
    public boolean miningPeerReady() {
        long response = lastHeaderResponseNanos;
        return running && active != null && response != 0
                && System.nanoTime() - response < TimeUnit.MINUTES.toNanos(2);
    }
    /** Execute on the session owner thread. Confirm possession by asking the
     * peer for the same full block; only then may the caller acknowledge its outbox.
     * Possession is propagation evidence, not proof of network-wide acceptance. */
    public void publishMinedBlock(RvnHash256 id, ru.bitcoin.node.rvn.chain.RvnBlock block) throws IOException {
        if (Thread.currentThread() == worker) throw new IllegalStateException("RVN relay cannot wait on its own session thread");
        var session = active;
        if (!running || session == null || session.isClosed()) throw new IOException("No connected RVN peer for mined block relay");
        var request = new MinedRelay(Objects.requireNonNull(id), Objects.requireNonNull(block), new CompletableFuture<>());
        if (!minedRelay.offer(request)) throw new IOException("RVN mined-block relay queue is busy");
        try { request.done().get(responseTimeoutMs * 2 + pollMs + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); request.done().cancel(true); session.close();
            throw new InterruptedIOException("RVN mined-block relay interrupted");
        } catch (ExecutionException | TimeoutException e) {
            request.done().cancel(true); session.close(); throw new IOException("RVN mined-block propagation not confirmed", e);
        }
    }
    private void drainMinedRelays(RvnPeerSession session) throws IOException, InterruptedException {
        MinedRelay request;
        while ((request = minedRelay.poll()) != null) {
            if (request.done().isDone()) continue;
            try {
                byte[] payload = ru.bitcoin.node.rvn.chain.RvnBlockCodec.serialize(request.block());
                session.send(new RvnMessage("block", payload));
                byte[] returned = RvnBlockClient.request(session, request.id().wire(), request.block().header(), responseTimeoutMs);
                if (!Arrays.equals(payload, returned)) throw new IOException("RVN peer returned a different mined-block body");
                request.done().complete(null);
            } catch (IOException | InterruptedException | RuntimeException e) { request.done().completeExceptionally(e); throw e; }
        }
    }
    private void failMinedRelays() {
        MinedRelay request;
        while ((request = minedRelay.poll()) != null)
            request.done().completeExceptionally(new IOException("RVN relay peer disconnected"));
    }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
