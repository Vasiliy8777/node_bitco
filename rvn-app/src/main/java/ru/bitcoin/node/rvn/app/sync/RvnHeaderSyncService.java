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
    private final ThreadPoolExecutor connector = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon(true).name("rvn-connect").factory());
    private volatile boolean running;
    private volatile RvnPeerSession active;
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
                String peer = peers.get(peerIndex++ % peers.size());
                try (RvnPeerSession session = connectBounded(peer)) {
                    active = session;
                    if (!running) break;
                    log.info("RVN header peer connected: {}:{}; local header height={}", peer, port, chain.best().height());
                    RvnHash256 cursor = chain.best().id();
                    long cursorTime = chain.best().timestamp();
                    int stagnant = 0, empty = 0;
                    while (running) {
                        var headers = RvnHeadersClient.request(session, 70028, chain.locators(cursor),
                                new byte[32], RvnActivation.MAINNET_KAWPOW_TIME, responseTimeoutMs);
                        if (headers.isEmpty()) {
                            if (downloadBodies(session, 32) > 0) { empty = 0; continue; }
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
                        downloadBodies(session, 8);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                } catch (IOException | SecurityException | IllegalArgumentException | RejectedExecutionException e) {
                    // Surface a poisoned local store as a fatal local error, not a peer retry.
                    if (running) chain.best();
                    if (running) log.warn("RVN header peer {}:{} failed: {}", peer, port, e.getMessage());
                    if (!running) break;
                    try { Thread.sleep(backoff); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    backoff = Math.min(60000, backoff * 2);
                } finally { active = null; }
            }
        } catch (RuntimeException fatal) {
            if (running && !Thread.currentThread().isInterrupted())
                log.error("RVN header synchronization stopped after a local failure", fatal);
        } finally { running = false; connector.shutdownNow(); }
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
            var session = RvnPeerSession.connect(RvnNetwork.MAINNET, host, port, timeoutMs, 256);
            pending.set(session);
            if (abandoned.get() || !running) {
                session.close(); throw new IOException("RVN connection cancelled");
            }
            return session;
        });
        try {
            var session = connection.get(timeoutMs * 2L + 1000, TimeUnit.MILLISECONDS);
            pending.set(null);
            return session;
        } catch (ExecutionException e) {
            throw new IOException("RVN connection failed", e.getCause());
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
        if (stopping != null && stopping != Thread.currentThread()) {
            stopping.interrupt();
            try { stopping.join(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
