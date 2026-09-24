package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic BIP31 ping/pong liveness probing for managed READY peers.
 *
 * Bitcoin Core v31.1 sends automatic pings every two minutes and treats an
 * outstanding ping older than the peer timeout (20 minutes by default) as a
 * dead connection.  This service keeps those policy timers outside Peer while
 * Peer owns the per-connection nonce and RTT state.
 */
public final class PeerLivenessService implements AutoCloseable {

    public static final Duration DEFAULT_PING_INTERVAL = Duration.ofMinutes(2);
    public static final Duration DEFAULT_PING_TIMEOUT = Duration.ofMinutes(20);
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(1);
    static final int MAX_PENDING_WRITES = 256;
    private static final long WRITE_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();

    private final ConcurrentHashMap<Peer, PingWrite> pendingWrites = new ConcurrentHashMap<>();

    private final PeerManager peerManager;
    private final long pingIntervalNanos;
    private final long pingTimeoutNanos;
    private final long checkIntervalMillis;
    private final Random random;

    private volatile boolean stopping;
    private Thread worker;

    public PeerLivenessService(PeerManager peerManager) {
        this(peerManager, DEFAULT_PING_INTERVAL, DEFAULT_PING_TIMEOUT,
                DEFAULT_CHECK_INTERVAL, new SecureRandom());
    }

    PeerLivenessService(
            PeerManager peerManager,
            Duration pingInterval,
            Duration pingTimeout,
            Duration checkInterval,
            Random random
    ) {
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager");
        Objects.requireNonNull(pingInterval, "pingInterval");
        Objects.requireNonNull(pingTimeout, "pingTimeout");
        Objects.requireNonNull(checkInterval, "checkInterval");
        this.random = Objects.requireNonNull(random, "random");

        if (pingInterval.isNegative() || pingInterval.isZero()) {
            throw new IllegalArgumentException("pingInterval must be positive");
        }
        if (pingTimeout.isNegative() || pingTimeout.isZero()) {
            throw new IllegalArgumentException("pingTimeout must be positive");
        }
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }

        pingIntervalNanos = pingInterval.toNanos();
        pingTimeoutNanos = pingTimeout.toNanos();
        checkIntervalMillis = Math.max(1L, checkInterval.toMillis());
    }

    public synchronized void start() {
        if (worker != null) return;
        if (stopping) throw new IllegalStateException("PeerLivenessService is stopping");

        worker = new Thread(this::run, "bitcoin-peer-liveness");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        while (!stopping) {
            checkPeers();
            try {
                Thread.sleep(checkIntervalMillis);
            } catch (InterruptedException ignored) {
                if (stopping) return;
            }
        }
    }

    void checkPeers() {
        if (stopping) return;
        long now = System.nanoTime();

        pendingWrites.forEach((peer, write) -> {
            if (now - write.started > Math.min(WRITE_TIMEOUT_NANOS, pingTimeoutNanos)) {
                write.fail(peer, "Peer ping write timeout");
            }
        });

        for (Peer peer : peerManager.readyPeers()) {
            if (peer.pingTimedOut(now, pingTimeoutNanos)) {
                peer.handleReaderFailure(new IOException("Peer ping timeout"));
                continue;
            }

            schedulePing(peer, now);
        }
    }

    private synchronized void schedulePing(Peer peer, long now) {
        if (stopping || pendingWrites.containsKey(peer)
                || pendingWrites.size() >= MAX_PENDING_WRITES
                || !peer.isPingDue(now, pingIntervalNanos)) return;
        PingWrite write = new PingWrite(now);
        long nonce = nextNonce();
        pendingWrites.put(peer, write);
        // Platform threads avoid pinning the Java 21 virtual-thread carrier in sendLock.
        // There is no queued work, and each peer can occupy only one bounded slot.
        Thread sender = new Thread(() -> {
            try {
                if (!stopping && !write.failed.get()) {
                    peer.sendPingIfDue(System.nanoTime(), pingIntervalNanos, nonce);
                }
            } catch (IOException | IllegalStateException failure) {
                peer.handleReaderFailure(new IOException("Peer ping write failed", failure));
            } finally {
                pendingWrites.remove(peer, write);
            }
        }, "bitcoin-peer-ping-write");
        sender.setDaemon(true);
        try {
            sender.start();
        } catch (RuntimeException | Error failure) {
            pendingWrites.remove(peer, write);
            throw failure;
        }
    }

    private static final class PingWrite {
        private final long started;
        private final AtomicBoolean failed = new AtomicBoolean();

        private PingWrite(long started) { this.started = started; }

        private void fail(Peer peer, String reason) {
            if (failed.compareAndSet(false, true)) {
                peer.handleReaderFailure(new IOException(reason));
            }
        }
    }

    private long nextNonce() {
        long nonce;
        do {
            nonce = random.nextLong();
        } while (nonce == 0L);
        return nonce;
    }

    @Override
    public void close() {
        List<java.util.Map.Entry<Peer, PingWrite>> writes;
        synchronized (this) {
            if (stopping) return;
            stopping = true;
            if (worker != null) worker.interrupt();
            writes = List.copyOf(pendingWrites.entrySet());
        }
        writes.forEach(entry -> entry.getValue().fail(entry.getKey(), "Peer liveness stopped during ping write"));
    }
}
