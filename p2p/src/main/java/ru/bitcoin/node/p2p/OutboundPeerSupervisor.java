package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Maintains one long-lived outbound peer connection.
 *
 * <p>The supervisor does not read from the socket itself.
 * PeerMessageReader remains the single socket reader.
 *
 * <p>When the currently supervised outbound peer closes,
 * the supervisor reconnects through OutboundPeerManager.
 */
public final class OutboundPeerSupervisor
        implements AutoCloseable {

    private static final System.Logger log =
            System.getLogger(
                    OutboundPeerSupervisor.class.getName()
            );

    private static final Duration DEFAULT_INITIAL_BACKOFF =
            Duration.ofSeconds(1);

    private static final Duration DEFAULT_MAX_BACKOFF =
            Duration.ofSeconds(30);

    private final OutboundPeerManager outboundPeerManager;
    private final IntSupplier startHeightSupplier;

    private final Duration initialBackoff;
    private final Duration maxBackoff;

    private final Object monitor =
            new Object();

    private final AtomicBoolean started =
            new AtomicBoolean();

    private volatile boolean stopping;

    private volatile OutboundPeerConnection connection;

    private Thread worker;

    public OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier
    ) {
        this(
                outboundPeerManager,
                startHeightSupplier,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF
        );
    }

    OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            Duration initialBackoff,
            Duration maxBackoff
    ) {
        this.outboundPeerManager =
                Objects.requireNonNull(
                        outboundPeerManager,
                        "outboundPeerManager"
                );

        this.startHeightSupplier =
                Objects.requireNonNull(
                        startHeightSupplier,
                        "startHeightSupplier"
                );

        this.initialBackoff =
                requirePositiveDuration(
                        initialBackoff,
                        "initialBackoff"
                );

        this.maxBackoff =
                requirePositiveDuration(
                        maxBackoff,
                        "maxBackoff"
                );

        if (this.initialBackoff.compareTo(
                this.maxBackoff
        ) > 0) {

            throw new IllegalArgumentException(
                    "initialBackoff must not exceed maxBackoff"
            );
        }
    }

    public void start(
            OutboundPeerConnection initialConnection
    ) {

        Objects.requireNonNull(
                initialConnection,
                "initialConnection"
        );

        if (!started.compareAndSet(
                false,
                true
        )) {

            throw new IllegalStateException(
                    "OutboundPeerSupervisor has already been started"
            );
        }

        synchronized (monitor) {

            if (stopping) {

                throw new IllegalStateException(
                        "OutboundPeerSupervisor is stopping"
                );
            }

            /*
             * Do not require the initial peer to still be READY.
             *
             * The peer may close after the successful connection
             * but before supervisor.start() is called.
             *
             * Peer.addCloseListener() supports late listener
             * registration and immediately notifies the listener
             * if the peer is already CLOSED.
             */
            connection =
                    initialConnection;

            registerCloseListener(
                    initialConnection
            );

            worker =
                    new Thread(
                            this::run,
                            "outbound-peer-supervisor"
                    );

            worker.setDaemon(
                    true
            );

            worker.start();

            monitor.notifyAll();
        }

        log.log(
                System.Logger.Level.INFO,
                "Outbound peer supervisor started for {0}",
                initialConnection.address()
        );
    }

    private void run() {

        while (!stopping) {

            waitForDisconnect();

            if (stopping) {
                return;
            }

            reconnectUntilSuccessful();
        }
    }

    private void waitForDisconnect() {

        synchronized (monitor) {

            while (!stopping
                    && connection != null) {

                try {

                    monitor.wait();

                } catch (InterruptedException exception) {

                    if (stopping) {
                        return;
                    }

                    Thread.currentThread()
                            .interrupt();

                    return;
                }
            }
        }
    }

    private void reconnectUntilSuccessful() {

        Duration delayBeforeNextAttempt =
                Duration.ZERO;

        int attempt =
                0;

        while (!stopping
                && connection == null) {

            if (!delayBeforeNextAttempt.isZero()) {

                log.log(
                        System.Logger.Level.INFO,
                        "Next outbound reconnect attempt in {0} ms",
                        delayBeforeNextAttempt.toMillis()
                );

                if (!waitForBackoff(
                        delayBeforeNextAttempt
                )) {
                    return;
                }

                if (connection != null) {
                    return;
                }
            }

            if (stopping) {
                return;
            }

            attempt++;

            try {

                int startHeight =
                        startHeightSupplier
                                .getAsInt();

                if (startHeight < 0) {

                    throw new IllegalStateException(
                            "startHeightSupplier returned negative height: "
                                    + startHeight
                    );
                }

                log.log(
                        System.Logger.Level.INFO,
                        "Outbound reconnect attempt #{0}, startHeight={1}",
                        attempt,
                        startHeight
                );

                OutboundPeerConnection newConnection =
                        outboundPeerManager
                                .connectOneWithAddress(
                                        startHeight,
                                        List.of()
                                );

                /*
                 * Shutdown may race with a successful connect.
                 * Never leave that newly-created peer alive.
                 */
                if (stopping) {

                    closeQuietly(
                            newConnection.peer()
                    );

                    return;
                }

                boolean installed =
                        installConnection(
                                newConnection
                        );

                if (!installed) {

                    closeQuietly(
                            newConnection.peer()
                    );

                    log.log(
                            System.Logger.Level.WARNING,
                            "Outbound reconnect attempt #{0} produced a peer "
                                    + "that closed before installation completed",
                            attempt
                    );

                    /*
                     * The outer worker loop will immediately start
                     * another reconnect cycle.
                     */
                    return;
                }

                log.log(
                        System.Logger.Level.INFO,
                        "Outbound peer reconnected successfully on attempt #{0}: {1}",
                        attempt,
                        newConnection.address()
                );

                return;

            } catch (IOException | RuntimeException exception) {

                if (stopping) {
                    return;
                }

                log.log(
                        System.Logger.Level.WARNING,
                        "Outbound reconnect attempt #{0} failed: {1}",
                        attempt,
                        exception.toString()
                );

                if (delayBeforeNextAttempt.isZero()) {

                    delayBeforeNextAttempt =
                            initialBackoff;

                } else {

                    delayBeforeNextAttempt =
                            nextBackoff(
                                    delayBeforeNextAttempt
                            );
                }
            }
        }
    }

    private boolean installConnection(
            OutboundPeerConnection newConnection
    ) {

        Objects.requireNonNull(
                newConnection,
                "newConnection"
        );

        synchronized (monitor) {

            if (stopping
                    || connection != null) {

                return false;
            }

            connection =
                    newConnection;

            /*
             * addCloseListener() may invoke the listener
             * synchronously if this peer is already CLOSED.
             */
            registerCloseListener(
                    newConnection
            );

            /*
             * If the peer closed during listener installation,
             * onPeerClosed() has already cleared connection.
             */
            return !stopping
                    && connection == newConnection;
        }
    }

    private void registerCloseListener(
            OutboundPeerConnection supervisedConnection
    ) {

        supervisedConnection
                .peer()
                .addCloseListener(
                        (peer, cause) ->
                                onPeerClosed(
                                        supervisedConnection,
                                        cause
                                )
                );
    }

    private void onPeerClosed(
            OutboundPeerConnection closedConnection,
            Throwable cause
    ) {

        boolean currentConnectionClosed =
                false;

        synchronized (monitor) {

            /*
             * Ignore callbacks belonging to an older connection.
             */
            if (connection
                    != closedConnection) {

                return;
            }

            connection =
                    null;

            currentConnectionClosed =
                    true;

            monitor.notifyAll();
        }

        if (currentConnectionClosed) {

            if (cause == null) {

                log.log(
                        System.Logger.Level.WARNING,
                        "Outbound peer disconnected: {0}",
                        closedConnection.address()
                );

            } else {

                log.log(
                        System.Logger.Level.WARNING,
                        "Outbound peer disconnected: {0}; cause: {1}",
                        closedConnection.address(),
                        cause.toString()
                );
            }
        }
    }

    private boolean waitForBackoff(
            Duration delay
    ) {

        long remainingNanos =
                delay.toNanos();

        long deadline =
                System.nanoTime()
                        + remainingNanos;

        synchronized (monitor) {

            while (!stopping
                    && connection == null
                    && remainingNanos > 0L) {

                long millis =
                        remainingNanos
                                / 1_000_000L;

                int nanos =
                        (int) (
                                remainingNanos
                                        % 1_000_000L
                        );

                try {

                    monitor.wait(
                            millis,
                            nanos
                    );

                } catch (InterruptedException exception) {

                    if (stopping) {
                        return false;
                    }

                    Thread.currentThread()
                            .interrupt();

                    return false;
                }

                remainingNanos =
                        deadline
                                - System.nanoTime();
            }

            return !stopping
                    && connection == null;
        }
    }

    private Duration nextBackoff(
            Duration current
    ) {

        if (current.compareTo(
                maxBackoff
        ) >= 0) {

            return maxBackoff;
        }

        Duration doubled;

        try {

            doubled =
                    current.multipliedBy(
                            2L
                    );

        } catch (ArithmeticException exception) {

            return maxBackoff;
        }

        if (doubled.compareTo(
                maxBackoff
        ) > 0) {

            return maxBackoff;
        }

        return doubled;
    }

    public boolean hasActiveConnection() {

        OutboundPeerConnection current =
                connection;

        return current != null
                && current.peer()
                .isReady();
    }

    public OutboundPeerConnection connection() {

        return connection;
    }

    public boolean isStarted() {

        return started.get();
    }

    public boolean isStopping() {

        return stopping;
    }

    @Override
    public void close() {

        Thread threadToJoin;

        synchronized (monitor) {

            if (stopping) {
                return;
            }

            stopping =
                    true;

            monitor.notifyAll();

            threadToJoin =
                    worker;
        }

        log.log(
                System.Logger.Level.INFO,
                "Stopping outbound peer supervisor"
        );

        if (threadToJoin != null) {

            threadToJoin.interrupt();

            if (threadToJoin
                    != Thread.currentThread()) {

                try {

                    threadToJoin.join(
                            5_000L
                    );

                } catch (InterruptedException exception) {

                    Thread.currentThread()
                            .interrupt();
                }
            }
        }

        synchronized (monitor) {

            /*
             * The active peer itself is intentionally not closed here.
             *
             * Lifecycle shutdown order:
             *
             * supervisor.close()
             * peerManager.close()
             *
             * This prevents normal node shutdown from starting
             * another reconnect cycle.
             */
            connection =
                    null;

            monitor.notifyAll();
        }

        log.log(
                System.Logger.Level.INFO,
                "Outbound peer supervisor stopped"
        );
    }

    private static Duration requirePositiveDuration(
            Duration duration,
            String name
    ) {

        Objects.requireNonNull(
                duration,
                name
        );

        if (duration.isZero()
                || duration.isNegative()) {

            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }

        return duration;
    }

    private static void closeQuietly(
            Peer peer
    ) {

        if (peer == null) {
            return;
        }

        try {

            peer.close();

        } catch (IOException ignored) {

            /*
             * Best-effort cleanup.
             */
        }
    }
}