package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.PeerAddress;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Maintains a fixed target number of long-lived outbound peer connections.
 *
 * <p>The supervisor never reads sockets itself. PeerMessageReader remains the
 * single socket reader for every Peer.
 *
 * <p>Each outbound slot is supervised independently. When a peer closes, only
 * that slot is reconnected. Connection establishment is serialized so two
 * empty slots cannot select the same address concurrently.
 */
public final class OutboundPeerSupervisor implements AutoCloseable {

    private static final System.Logger log =
            System.getLogger(OutboundPeerSupervisor.class.getName());

    private static final Duration DEFAULT_FEELER_INTERVAL =
            Duration.ofMinutes(
                    2
            );

    private static final Duration DEFAULT_FEELER_JITTER =
            Duration.ofSeconds(
                    1
            );

    private static final Duration DEFAULT_INITIAL_BACKOFF =
            Duration.ofSeconds(1);

    private static final Duration DEFAULT_MAX_BACKOFF =
            Duration.ofSeconds(30);

    private static final int DEFAULT_TARGET_OUTBOUND_PEERS =
            1;

    private final OutboundPeerManager outboundPeerManager;
    private final IntSupplier startHeightSupplier;
    private final int targetOutboundPeers;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    /*
     * TRIED-collision feeler scheduling.
     *
     * Feeler connections are independent from the fixed set of
     * long-lived outbound slots.
     */
    private final Duration feelerInterval;
    private final Duration feelerJitter;

    private final Object monitor =
            new Object();

    private final Object connectLock =
            new Object();

    private final AtomicBoolean started =
            new AtomicBoolean();

    private final List<Slot> slots =
            new ArrayList<>();

    private volatile boolean stopping;

    /*
     * Dedicated worker for short-lived feeler connections.
     * It is deliberately not represented by a normal outbound Slot.
     */
    private Thread feelerWorker;

    public OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier
    ) {
        this(
                outboundPeerManager,
                startHeightSupplier,
                DEFAULT_TARGET_OUTBOUND_PEERS,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF
        );
    }

    public OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            int targetOutboundPeers
    ) {
        this(
                outboundPeerManager,
                startHeightSupplier,
                targetOutboundPeers,
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
        this(
                outboundPeerManager,
                startHeightSupplier,
                DEFAULT_TARGET_OUTBOUND_PEERS,
                initialBackoff,
                maxBackoff
        );
    }

    OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            int targetOutboundPeers,
            Duration initialBackoff,
            Duration maxBackoff
    ) {
        this(
                outboundPeerManager,
                startHeightSupplier,
                targetOutboundPeers,
                initialBackoff,
                maxBackoff,
                DEFAULT_FEELER_INTERVAL,
                DEFAULT_FEELER_JITTER
        );
    }

    OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            int targetOutboundPeers,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration feelerInterval,
            Duration feelerJitter
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

        if (targetOutboundPeers <= 0) {

            throw new IllegalArgumentException(
                    "targetOutboundPeers must be positive"
            );
        }

        this.targetOutboundPeers =
                targetOutboundPeers;

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

        this.feelerInterval =
                requirePositiveDuration(
                        feelerInterval,
                        "feelerInterval"
                );

        Objects.requireNonNull(
                feelerJitter,
                "feelerJitter"
        );

        if (feelerJitter.isNegative()) {

            throw new IllegalArgumentException(
                    "feelerJitter must not be negative"
            );
        }

        this.feelerJitter =
                feelerJitter;
    }

    public void start(OutboundPeerConnection initialConnection) {
        Objects.requireNonNull(initialConnection, "initialConnection");

        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("OutboundPeerSupervisor has already been started");
        }

        synchronized (monitor) {
            if (stopping) {
                throw new IllegalStateException("OutboundPeerSupervisor is stopping");
            }

            for (int index = 0; index < targetOutboundPeers; index++) {
                slots.add(new Slot(index));
            }

            Slot initialSlot = slots.get(0);
            initialSlot.connection = initialConnection;
            registerCloseListener(initialSlot, initialConnection);

            for (Slot slot : slots) {
                Thread worker = new Thread(
                        () -> runSlot(slot),
                        "outbound-peer-supervisor-" + slot.index
                );
                worker.setDaemon(true);
                slot.worker = worker;
                worker.start();
            }

            Thread worker =
                    new Thread(
                            this::runFeelerLoop,
                            "outbound-peer-feeler"
                    );

            worker.setDaemon(
                    true
            );

            feelerWorker =
                    worker;

            worker.start();

            monitor.notifyAll();
        }

        log.log(
                System.Logger.Level.INFO,
                "Outbound peer supervisor started with target={0}; initial peer={1}",
                targetOutboundPeers,
                initialConnection.address()
        );
    }

    private void runFeelerLoop() {

        while (!stopping) {

            if (!waitForFeelerInterval()) {
                return;
            }

            if (stopping) {
                return;
            }

            try {

                int startHeight =
                        startHeightSupplier.getAsInt();

                if (startHeight < 0) {

                    log.log(
                            System.Logger.Level.WARNING,
                            "Skipping feeler: startHeightSupplier returned negative height: {0}",
                            startHeight
                    );

                    continue;
                }

                boolean processed;

                /*
                 * Serialize connection establishment with persistent
                 * outbound slots. This prevents simultaneous socket
                 * establishment from racing through AddrMan state.
                 */
                synchronized (connectLock) {

                    if (stopping) {
                        return;
                    }

                    processed =
                            outboundPeerManager
                                    .tryTriedCollisionFeeler(
                                            startHeight
                                    );
                }

                if (processed) {

                    log.log(
                            System.Logger.Level.DEBUG,
                            "TRIED-collision feeler completed"
                    );
                }

            } catch (IOException exception) {

                if (!stopping) {

                    log.log(
                            System.Logger.Level.DEBUG,
                            "TRIED-collision feeler failed: {0}",
                            exception.toString()
                    );
                }

            } catch (RuntimeException exception) {

                if (!stopping) {

                    log.log(
                            System.Logger.Level.WARNING,
                            "TRIED-collision feeler processing failed: {0}",
                            exception.toString()
                    );
                }
            }
        }
    }

    private boolean waitForFeelerInterval() {

        long intervalNanos =
                feelerInterval.toNanos();

        long jitterNanos =
                feelerJitter.toNanos();

        long extraNanos =
                jitterNanos == 0L
                        ? 0L
                        : ThreadLocalRandom
                        .current()
                        .nextLong(
                                jitterNanos + 1L
                        );

        long waitNanos;

        try {

            waitNanos =
                    Math.addExact(
                            intervalNanos,
                            extraNanos
                    );

        } catch (ArithmeticException exception) {

            waitNanos =
                    Long.MAX_VALUE;
        }

        long deadline =
                System.nanoTime()
                        + waitNanos;

        synchronized (monitor) {

            while (!stopping
                    && waitNanos > 0L) {

                long millis =
                        waitNanos
                                / 1_000_000L;

                int nanos =
                        (int) (
                                waitNanos
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

                waitNanos =
                        deadline
                                - System.nanoTime();
            }

            return !stopping;
        }
    }

    private void runSlot(Slot slot) {
        while (!stopping) {
            waitForEmptySlot(slot);
            if (stopping) {
                return;
            }
            reconnectUntilSuccessful(slot);
        }
    }

    private void waitForEmptySlot(Slot slot) {
        synchronized (monitor) {
            while (!stopping && slot.connection != null) {
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    if (stopping) {
                        return;
                    }
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void reconnectUntilSuccessful(Slot slot) {
        Duration delayBeforeNextAttempt = Duration.ZERO;
        int attempt = 0;

        while (!stopping && isSlotEmpty(slot)) {
            if (!delayBeforeNextAttempt.isZero()) {
                log.log(
                        System.Logger.Level.INFO,
                        "Next outbound reconnect attempt for slot {0} in {1} ms",
                        slot.index,
                        delayBeforeNextAttempt.toMillis()
                );

                if (!waitForBackoff(slot, delayBeforeNextAttempt)) {
                    return;
                }

                if (!isSlotEmpty(slot)) {
                    return;
                }
            }

            if (stopping) {
                return;
            }

            attempt++;

            try {
                OutboundPeerConnection newConnection = connectForSlot(slot, attempt);
                if (newConnection == null) {
                    return;
                }

                if (stopping) {
                    closeQuietly(newConnection.peer());
                    return;
                }

                boolean installed = installConnection(slot, newConnection);
                if (!installed) {
                    closeQuietly(newConnection.peer());
                    log.log(
                            System.Logger.Level.WARNING,
                            "Outbound reconnect attempt #{0} for slot {1} produced a peer "
                                    + "that closed before installation completed",
                            attempt,
                            slot.index
                    );
                    return;
                }

                log.log(
                        System.Logger.Level.INFO,
                        "Outbound peer reconnected successfully for slot {0} on attempt #{1}: {2}",
                        slot.index,
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
                        "Outbound reconnect attempt #{0} for slot {1} failed: {2}",
                        attempt,
                        slot.index,
                        exception.toString()
                );

                delayBeforeNextAttempt = delayBeforeNextAttempt.isZero()
                        ? initialBackoff
                        : nextBackoff(delayBeforeNextAttempt);
            }
        }
    }

    private OutboundPeerConnection connectForSlot(Slot slot, int attempt)
            throws IOException {

        synchronized (connectLock) {
            if (stopping || !isSlotEmpty(slot)) {
                return null;
            }

            int startHeight = startHeightSupplier.getAsInt();
            if (startHeight < 0) {
                throw new IllegalStateException(
                        "startHeightSupplier returned negative height: " + startHeight
                );
            }

            List<PeerAddress> excludedAddresses = occupiedAddresses(slot);

            log.log(
                    System.Logger.Level.INFO,
                    "Outbound reconnect attempt #{0} for slot {1}, startHeight={2}, excluded={3}",
                    attempt,
                    slot.index,
                    startHeight,
                    excludedAddresses.size()
            );

            return outboundPeerManager.connectOneWithAddress(
                    startHeight,
                    excludedAddresses
            );
        }
    }

    private List<PeerAddress> occupiedAddresses(Slot exceptSlot) {
        synchronized (monitor) {
            List<PeerAddress> result = new ArrayList<>();
            for (Slot slot : slots) {
                if (slot == exceptSlot || slot.connection == null) {
                    continue;
                }
                result.add(slot.connection.address());
            }
            return List.copyOf(result);
        }
    }

    private boolean installConnection(Slot slot, OutboundPeerConnection newConnection) {
        Objects.requireNonNull(newConnection, "newConnection");

        synchronized (monitor) {
            if (stopping || slot.connection != null) {
                return false;
            }

            for (Slot other : slots) {
                if (other != slot
                        && other.connection != null
                        && other.connection.address().equals(newConnection.address())) {
                    return false;
                }
            }

            slot.connection = newConnection;
            registerCloseListener(slot, newConnection);

            return !stopping && slot.connection == newConnection;
        }
    }

    private void registerCloseListener(
            Slot slot,
            OutboundPeerConnection supervisedConnection
    ) {
        supervisedConnection.peer().addCloseListener(
                (peer, cause) -> onPeerClosed(slot, supervisedConnection, cause)
        );
    }

    private void onPeerClosed(
            Slot slot,
            OutboundPeerConnection closedConnection,
            Throwable cause
    ) {
        boolean currentConnectionClosed = false;

        synchronized (monitor) {
            if (slot.connection != closedConnection) {
                return;
            }

            slot.connection = null;
            currentConnectionClosed = true;
            monitor.notifyAll();
        }

        if (currentConnectionClosed) {
            if (cause == null) {
                log.log(
                        System.Logger.Level.WARNING,
                        "Outbound peer disconnected from slot {0}: {1}",
                        slot.index,
                        closedConnection.address()
                );
            } else {
                log.log(
                        System.Logger.Level.WARNING,
                        "Outbound peer disconnected from slot {0}: {1}; cause: {2}",
                        slot.index,
                        closedConnection.address(),
                        cause.toString()
                );
            }
        }
    }

    private boolean waitForBackoff(Slot slot, Duration delay) {
        long remainingNanos = delay.toNanos();
        long deadline = System.nanoTime() + remainingNanos;

        synchronized (monitor) {
            while (!stopping && slot.connection == null && remainingNanos > 0L) {
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);

                try {
                    monitor.wait(millis, nanos);
                } catch (InterruptedException exception) {
                    if (stopping) {
                        return false;
                    }
                    Thread.currentThread().interrupt();
                    return false;
                }

                remainingNanos = deadline - System.nanoTime();
            }

            return !stopping && slot.connection == null;
        }
    }

    private boolean isSlotEmpty(Slot slot) {
        synchronized (monitor) {
            return slot.connection == null;
        }
    }

    private Duration nextBackoff(Duration current) {
        if (current.compareTo(maxBackoff) >= 0) {
            return maxBackoff;
        }

        Duration doubled;
        try {
            doubled = current.multipliedBy(2L);
        } catch (ArithmeticException exception) {
            return maxBackoff;
        }

        return doubled.compareTo(maxBackoff) > 0
                ? maxBackoff
                : doubled;
    }

    public boolean hasActiveConnection() {
        return activeConnectionCount() > 0;
    }

    /**
     * Compatibility accessor for the original single-peer API.
     * Returns the first occupied slot, or null if all slots are empty.
     */
    public OutboundPeerConnection connection() {
        synchronized (monitor) {
            for (Slot slot : slots) {
                if (slot.connection != null) {
                    return slot.connection;
                }
            }
            return null;
        }
    }

    public List<OutboundPeerConnection> connections() {
        synchronized (monitor) {
            List<OutboundPeerConnection> result = new ArrayList<>();
            for (Slot slot : slots) {
                if (slot.connection != null) {
                    result.add(slot.connection);
                }
            }
            return List.copyOf(result);
        }
    }

    public int activeConnectionCount() {
        synchronized (monitor) {
            int count = 0;
            for (Slot slot : slots) {
                if (slot.connection != null && slot.connection.peer().isReady()) {
                    count++;
                }
            }
            return count;
        }
    }

    public int targetOutboundPeers() {
        return targetOutboundPeers;
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isStopping() {
        return stopping;
    }

    @Override
    public void close() {
        List<Thread> threadsToJoin;

        synchronized (monitor) {

            if (stopping) {
                return;
            }

            stopping =
                    true;

            monitor.notifyAll();

            threadsToJoin =
                    new ArrayList<>();

            for (Slot slot :
                    slots) {

                if (slot.worker != null) {

                    threadsToJoin.add(
                            slot.worker
                    );
                }
            }

            if (feelerWorker != null) {

                threadsToJoin.add(
                        feelerWorker
                );
            }
        }

        log.log(System.Logger.Level.INFO, "Stopping outbound peer supervisor");

        for (Thread thread : threadsToJoin) {
            thread.interrupt();
        }

        for (Thread thread : threadsToJoin) {
            if (thread == Thread.currentThread()) {
                continue;
            }
            try {
                thread.join(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        synchronized (monitor) {
            /*
             * Active peers are intentionally not closed here.
             * Lifecycle shutdown order remains:
             * supervisor.close() -> peerManager.close().
             */
            for (Slot slot : slots) {
                slot.connection = null;
            }
            monitor.notifyAll();
        }

        log.log(System.Logger.Level.INFO, "Outbound peer supervisor stopped");
    }

    private static Duration requirePositiveDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static void closeQuietly(Peer peer) {
        if (peer == null) {
            return;
        }
        try {
            peer.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static final class Slot {
        private final int index;
        private OutboundPeerConnection connection;
        private Thread worker;

        private Slot(int index) {
            this.index = index;
        }
    }
}
