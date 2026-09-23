package ru.bitcoin.node.stratum;

import ru.bitcoin.node.stratum.job.*;
import ru.bitcoin.node.stratum.share.ShareValidator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded solo-mining Stratum V1 TCP server. No pool balances or payout accounting. */
public final class StratumServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StratumServer.class.getName());
    private final ServerSocket listener;
    private final MiningBackend backend;
    private final String user;
    private final byte[] password;
    private final BigDecimal difficulty;
    private final ShareValidator validator;
    private final MiningJobManager jobs = new MiningJobManager();
    private final ExtraNonceManager extraNonces = new ExtraNonceManager();
    private final Set<StratumSession> sessions = ConcurrentHashMap.newKeySet();
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService updater = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("stratum-templates").factory());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("stratum-write-timeouts").factory());
    private final Thread acceptor;
    private final LongAdder acceptedShares = new LongAdder();
    private final LongAdder rejectedShares = new LongAdder();
    private final LongAdder blocks = new LongAdder();
    private volatile MiningJob current;
    private volatile boolean closed;

    public StratumServer(InetSocketAddress address, MiningBackend backend, String user, String password,
                         BigDecimal difficulty, int maximumConnections) throws IOException {
        this.backend = java.util.Objects.requireNonNull(backend);
        if (user == null || user.isBlank() || user.length() > 64 || password == null || password.isBlank())
            throw new IllegalArgumentException("Stratum user and password are required");
        if (maximumConnections < 1 || maximumConnections > 1024) throw new IllegalArgumentException("Invalid connection limit");
        this.user = user;
        this.password = password.getBytes(StandardCharsets.UTF_8);
        this.difficulty = difficulty;
        validator = new ShareValidator(difficulty);
        slots = new Semaphore(maximumConnections);
        listener = new ServerSocket();
        try { listener.bind(address, maximumConnections); }
        catch (IOException | RuntimeException exception) { listener.close(); throw exception; }
        acceptor = Thread.ofPlatform().daemon().name("stratum-accept").start(this::accept);
        updater.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
    }

    public int port() { return listener.getLocalPort(); }
    MiningBackend backend() { return backend; }
    MiningJobManager jobs() { return jobs; }
    ShareValidator validator() { return validator; }
    BigDecimal difficulty() { return difficulty; }
    MiningJob current() { return current; }

    boolean authorize(String name, String suppliedPassword) {
        return name.length() <= 128 && (name.equals(user) || name.startsWith(user + "."))
                && MessageDigest.isEqual(password, suppliedPassword.getBytes(StandardCharsets.UTF_8));
    }

    void accepted(boolean block) { acceptedShares.increment(); if (block) blocks.increment(); }
    void rejected() { rejectedShares.increment(); }

    public record Statistics(int connections, long acceptedShares, long rejectedShares, long submittedBlocks) { }
    public Statistics statistics() { return new Statistics(sessions.size(), acceptedShares.sum(), rejectedShares.sum(), blocks.sum()); }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                if (closed || !slots.tryAcquire()) { socket.close(); continue; }
                try {
                    socket.setSoTimeout(180_000);
                    socket.setTcpNoDelay(true);
                    var session = new StratumSession(this, socket, extraNonces.next());
                    sessions.add(session);
                    try {
                        workers.execute(() -> {
                            try { session.run(workers, watchdog); }
                            finally { sessions.remove(session); slots.release(); }
                        });
                    } catch (RejectedExecutionException exception) {
                        sessions.remove(session);
                        throw exception;
                    }
                } catch (IOException | RuntimeException exception) {
                    slots.release();
                    socket.close();
                    if (!closed) LOG.log(System.Logger.Level.WARNING, "Stratum connection failed", exception);
                }
            } catch (IOException exception) {
                if (!closed) LOG.log(System.Logger.Level.WARNING, "Stratum accept failed", exception);
            }
        }
    }

    private void refresh() {
        if (closed) return;
        try {
            var work = backend.work();
            if (work.isEmpty()) { unavailable(); return; }
            MiningJob next = jobs.update(work.get());
            current = next;
            for (var session : sessions) session.publish(next);
        } catch (RuntimeException exception) {
            unavailable();
            LOG.log(System.Logger.Level.ERROR, "Unable to build Stratum work", exception);
        }
    }

    private void unavailable() {
        current = null;
        jobs.clear();
        for (var session : sessions) session.invalidate();
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        listener.close();
        sessions.forEach(StratumSession::close);
        updater.shutdownNow();
        workers.shutdownNow();
        watchdog.shutdownNow();
        try {
            acceptor.join(5_000);
            if (!workers.awaitTermination(5, TimeUnit.SECONDS) || !updater.awaitTermination(5, TimeUnit.SECONDS))
                throw new IOException("Stratum workers did not stop");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping Stratum", exception);
        }
    }
}
