package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;

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
        long now = System.nanoTime();

        for (Peer peer : peerManager.readyPeers()) {
            if (peer.pingTimedOut(now, pingTimeoutNanos)) {
                peer.handleReaderFailure(new IOException("Peer ping timeout"));
                continue;
            }

            try {
                peer.sendPingIfDue(now, pingIntervalNanos, nextNonce());
            } catch (IOException failure) {
                peer.handleReaderFailure(failure);
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
    public synchronized void close() {
        if (stopping) return;
        stopping = true;
        Thread current = worker;
        if (current != null) current.interrupt();
    }
}
