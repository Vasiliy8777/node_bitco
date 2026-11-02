package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerNetGroup;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.EnumMap;
import java.io.EOFException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Maintains the outbound pool, with an optional extra full-relay recovery slot.
 *
 * <p>The supervisor never reads sockets itself. PeerMessageReader remains the
 * single socket reader for every Peer.
 *
 * <p>Each outbound slot is supervised independently. When a peer closes, only
 * that slot is reconnected. Candidate reservation is serialized to keep endpoint
 * and netgroup selection unique, while network establishment runs concurrently.
 */
public final class OutboundPeerSupervisor implements AutoCloseable {
    private OutboundPeerRecoveryView recoveryView;
    private ExtraOutboundPeerPolicy recoveryPolicy;
    private java.util.concurrent.ScheduledExecutorService recoveryTimer;
    private long nextExtraEvictionNanos;
    private final Set<Peer> protectedRecoveryPeers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private volatile boolean tryExtra;
    private volatile boolean tryBlock;
    private boolean blockRotationStarted;
    private long nextBlockProbeMicros;
    private long nextNetworkProbeMicros;
    private volatile ru.bitcoin.node.p2p.address.PeerAddressNetwork preferredNetwork;
    private java.util.function.LongSupplier networkProbeDelayMicros = ExtraOutboundPeerPolicy::randomProbeDelayMicros;
    private java.util.function.LongSupplier blockProbeDelayMicros = ExtraOutboundPeerPolicy::randomProbeDelayMicros;
    private java.util.function.LongSupplier recoveryTimeMicros = PeerConnection::currentTimeMicros;
    private int maximumAutomaticConnections = Integer.MAX_VALUE;

    OutboundPeerSupervisor recoveryTimeMicros(java.util.function.LongSupplier clock) {
        synchronized (monitor) {
            if (started.get() || stopping) throw new IllegalStateException("Configure clock before starting");
            recoveryTimeMicros = Objects.requireNonNull(clock);
        }
        return this;
    }

    public OutboundPeerSupervisor maximumAutomaticConnections(int maximum) {
        synchronized (monitor) {
            if (started.get() || stopping) throw new IllegalStateException("Configure connection capacity before starting");
            if (maximum < targetOutboundPeers + targetBlockRelayPeers)
                throw new IllegalArgumentException("Connection capacity is below the normal outbound pool");
            maximumAutomaticConnections = maximum;
        }
        return this;
    }

    OutboundPeerSupervisor networkProbeDelayMicros(java.util.function.LongSupplier delay) {
        synchronized (monitor) {
            if (started.get()) throw new IllegalStateException("Configure probe timing before starting");
            networkProbeDelayMicros = Objects.requireNonNull(delay);
        }
        return this;
    }

    OutboundPeerSupervisor blockProbeDelayMicros(java.util.function.LongSupplier delay) {
        synchronized (monitor) {
            if (started.get()) throw new IllegalStateException("Configure probe timing before starting");
            blockProbeDelayMicros = Objects.requireNonNull(delay);
        }
        return this;
    }

    public OutboundPeerSupervisor recovery(OutboundPeerRecoveryView view, long targetSpacingSeconds) {
        synchronized (monitor) {
            if (started.get() || stopping) throw new IllegalStateException("Configure recovery before starting the supervisor");
            recoveryView = Objects.requireNonNull(view);
            recoveryPolicy = new ExtraOutboundPeerPolicy(targetSpacingSeconds);
        }
        return this;
    }
    private BlockRelayAnchorStore anchorStore;
    private final java.util.ArrayDeque<PeerAddress> pendingAnchors = new java.util.ArrayDeque<>();

    public OutboundPeerSupervisor anchorStore(BlockRelayAnchorStore store) {
        synchronized (monitor) {
            if (started.get() || stopping) throw new IllegalStateException("Configure anchors before starting the supervisor");
            anchorStore = Objects.requireNonNull(store);
        }
        return this;
    }

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
    private final int targetBlockRelayPeers;
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

    /*
     * Serializes only AddrMan candidate reservation. Network establishment must
     * never run under this lock: every empty outbound slot connects in parallel.
     */
    private final Object connectLock =
            new Object();

    private final Set<PeerAddress> connectingAddresses =
            new LinkedHashSet<>();

    private final Set<PeerNetGroup> connectingNetGroups =
            new LinkedHashSet<>();

    private final AtomicBoolean started =
            new AtomicBoolean();

    private final List<Slot> slots =
            new ArrayList<>();

    private volatile boolean stopping;

    private final EnumMap<PeerCloseReason, Long> closeReasonCounts =
            new EnumMap<>(PeerCloseReason.class);
    private long successfulReplacements;

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
        this(outboundPeerManager, startHeightSupplier, targetOutboundPeers, 0, initialBackoff, maxBackoff, feelerInterval, feelerJitter);
    }

    public OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            int targetOutboundPeers,
            int targetBlockRelayPeers
    ) {
        this(outboundPeerManager, startHeightSupplier, targetOutboundPeers, targetBlockRelayPeers,
                DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_FEELER_INTERVAL, DEFAULT_FEELER_JITTER);
    }

    OutboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            IntSupplier startHeightSupplier,
            int targetOutboundPeers,
            int targetBlockRelayPeers,
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

        if (targetOutboundPeers < 0 || (targetOutboundPeers == 0 && targetBlockRelayPeers > 0)) {

            throw new IllegalArgumentException(
                    "Full-relay target must be positive unless all automatic outbound connections are disabled"
            );
        }

        this.targetOutboundPeers =
                targetOutboundPeers;
        if (targetBlockRelayPeers < 0) {
            throw new IllegalArgumentException("targetBlockRelayPeers must not be negative");
        }
        this.targetBlockRelayPeers = targetBlockRelayPeers;

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

            if (anchorStore != null && targetBlockRelayPeers > 0) {
                try {
                    pendingAnchors.addAll(anchorStore.consume());
                    log.log(System.Logger.Level.INFO, "Loaded {0} one-shot block relay anchors", pendingAnchors.size());
                }
                catch (IOException failure) { log.log(System.Logger.Level.WARNING, "Ignoring unreadable block relay anchors", failure); }
            }

            for (int index = 0; index < targetOutboundPeers; index++) {
                slots.add(new Slot(index, PeerConnectionRole.FULL_RELAY));
            }
            for (int index = 0; index < targetBlockRelayPeers; index++) {
                slots.add(new Slot(targetOutboundPeers + index, PeerConnectionRole.BLOCK_RELAY_ONLY));
            }
            if (recoveryView != null) {
                nextNetworkProbeMicros = recoveryTimeMicros.getAsLong() + Math.max(0, networkProbeDelayMicros.getAsLong());
                Slot extra = new Slot(slots.size(), PeerConnectionRole.FULL_RELAY);
                extra.extra = true;
                slots.add(extra);
                if (targetBlockRelayPeers > 0) {
                    Slot blockExtra = new Slot(slots.size(), PeerConnectionRole.BLOCK_RELAY_ONLY);
                    blockExtra.extra = true;
                    slots.add(blockExtra);
                    nextBlockProbeMicros = recoveryTimeMicros.getAsLong() + Math.max(0, blockProbeDelayMicros.getAsLong());
                }
            }

            Slot initialSlot = slots.get(0);
            initialSlot.connection = initialConnection;
            initialSlot.connectionId = initialConnection.peer().connectionId();
            initialSlot.connectedAtMicros = initialConnection.peer().connectionEstablishedAtMicros();
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

        synchronized (monitor) {
            if (recoveryView != null && !stopping) {
                recoveryTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("outbound-peer-recovery").factory());
                nextExtraEvictionNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                recoveryTimer.scheduleWithFixedDelay(() -> {
                    try {
                        long nowNanos = System.nanoTime();
                        boolean evict = nowNanos - nextExtraEvictionNanos >= 0;
                        if (evict) nextExtraEvictionNanos = nowNanos + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                        checkRecoveryAtMicros(recoveryTimeMicros.getAsLong(), evict);
                    }
                    catch (RuntimeException failure) { log.log(System.Logger.Level.WARNING, "Outbound recovery check failed", failure); }
                }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
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

                if (stopping) {
                    return;
                }

                /*
                 * Feeler I/O must not block persistent slot reservations. AddrMan
                 * itself owns its state synchronization; persistent endpoint
                 * uniqueness is enforced by the reservation sets above.
                 */
                boolean processed =
                        outboundPeerManager
                                .tryTriedCollisionFeeler(
                                        startHeight
                                );

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
            while (!stopping && (slot.connection != null || !canConnectSlot(slot))) {
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

        while (!stopping && canConnectSlot(slot) && isSlotEmpty(slot)) {
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

                startRecoveryHeaders(slot, newConnection);
                synchronized (monitor) { successfulReplacements++; }
                log.log(
                        System.Logger.Level.INFO,
                        "Outbound peer reconnected successfully for slot {0} on attempt #{1}: {2}; {3}",
                        slot.index, attempt, newConnection.address(), poolDiagnostics()
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

                /*
                 * Back off after every failed replacement attempt. Empty slots are
                 * independent and reconnect in parallel, so a per-slot backoff does not
                 * serialize pool recovery and prevents reconnect storms against a weak
                 * AddrMan candidate set.
                 */
                delayBeforeNextAttempt = delayBeforeNextAttempt.isZero()
                        ? initialBackoff
                        : nextBackoff(delayBeforeNextAttempt);
            } finally {
                synchronized (monitor) { slot.connecting = false; monitor.notifyAll(); }
            }
        }
    }

    private OutboundPeerConnection connectForSlot(Slot slot, int attempt)
            throws IOException {

        final int startHeight;
        final PeerAddress reservedAddress;
        final ru.bitcoin.node.p2p.address.PeerAddressNetwork networkPreference;

        synchronized (connectLock) {
            if (stopping || !canConnectSlot(slot) || !isSlotEmpty(slot)) {
                return null;
            }

            startHeight = startHeightSupplier.getAsInt();
            if (startHeight < 0) {
                throw new IllegalStateException(
                        "startHeightSupplier returned negative height: " + startHeight
                );
            }

            if (slot.extra) synchronized (monitor) {
                slot.connecting = true;
                if (slot.role == PeerConnectionRole.BLOCK_RELAY_ONLY) tryBlock = false;
            }
            synchronized (monitor) {
                networkPreference = slot.extra && slot.role == PeerConnectionRole.FULL_RELAY && !tryExtra ? preferredNetwork : null;
                if (networkPreference != null) preferredNetwork = null;
            }
            List<PeerAddress> occupied = occupiedAddresses(slot);
            Set<PeerNetGroup> occupiedGroups = occupiedNetGroups(slot);

            LinkedHashSet<PeerAddress> excludedAddresses = new LinkedHashSet<>(occupied);
            excludedAddresses.addAll(connectingAddresses);

            LinkedHashSet<PeerNetGroup> excludedNetGroups = new LinkedHashSet<>(occupiedGroups);
            excludedNetGroups.addAll(connectingNetGroups);

            PeerAddress anchor = null;
            if (slot.role == PeerConnectionRole.BLOCK_RELAY_ONLY) {
                while (!pendingAnchors.isEmpty()) {
                    var candidate = pendingAnchors.removeLast();
                    if (excludedAddresses.contains(candidate)
                            || (PeerNetGroup.isDiversifiable(candidate) && excludedNetGroups.contains(PeerNetGroup.of(candidate)))
                            || !outboundPeerManager.prepareAnchor(candidate)) continue;
                    anchor = candidate; break;
                }
            } else {
                // Full-relay workers must not take endpoints reserved for anchor attempts.
                excludedAddresses.addAll(pendingAnchors);
                for (var candidate : pendingAnchors) if (PeerNetGroup.isDiversifiable(candidate))
                    excludedNetGroups.add(PeerNetGroup.of(candidate));
            }
            reservedAddress = anchor != null ? anchor : networkPreference == null
                    ? outboundPeerManager.selectPersistentCandidate(List.copyOf(excludedAddresses), Set.copyOf(excludedNetGroups))
                    : outboundPeerManager.selectPersistentCandidate(List.copyOf(excludedAddresses), Set.copyOf(excludedNetGroups), networkPreference);
            if (anchor != null) log.log(System.Logger.Level.INFO, "Trying block relay anchor {0}", anchor);

            connectingAddresses.add(reservedAddress);
            if (PeerNetGroup.isDiversifiable(reservedAddress)) {
                connectingNetGroups.add(PeerNetGroup.of(reservedAddress));
            }

            log.log(
                    System.Logger.Level.INFO,
                    "Outbound slot {0} reserved candidate {1}, role={2}, attempt={3}, connecting={4}, active={5}",
                    slot.index, reservedAddress, slot.role, attempt,
                    connectingAddresses.size(), activeConnectionCount()
            );
        }

        try {
            // Deliberately outside connectLock: TCP + transport + VERSION/VERACK
            // for different slots must proceed concurrently.
            return outboundPeerManager.connectReservedPersistentCandidate(
                    startHeight,
                    reservedAddress,
                    slot.role
            );
        } finally {
            synchronized (connectLock) {
                connectingAddresses.remove(reservedAddress);
                rebuildConnectingNetGroups();
            }
        }
    }

    private void rebuildConnectingNetGroups() {
        connectingNetGroups.clear();
        for (PeerAddress address : connectingAddresses) {
            if (PeerNetGroup.isDiversifiable(address)) {
                connectingNetGroups.add(PeerNetGroup.of(address));
            }
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

    private Set<PeerNetGroup> occupiedNetGroups(Slot exceptSlot) {
        synchronized (monitor) {
            Set<PeerNetGroup> result = new LinkedHashSet<>();
            for (Slot slot : slots) {
                if (slot == exceptSlot || slot.connection == null) {
                    continue;
                }
                PeerAddress address = slot.connection.address();
                if (PeerNetGroup.isDiversifiable(address)) {
                    result.add(PeerNetGroup.of(address));
                }
            }
            return Set.copyOf(result);
        }
    }

    private boolean installConnection(Slot slot, OutboundPeerConnection newConnection) {
        Objects.requireNonNull(newConnection, "newConnection");

        synchronized (monitor) {
            if (stopping || slot.connection != null) {
                return false;
            }
            if (newConnection.role() != slot.role) {
                return false;
            }

            for (Slot other : slots) {
                if (other == slot || other.connection == null) {
                    continue;
                }

                PeerAddress otherAddress = other.connection.address();
                PeerAddress candidateAddress = newConnection.address();

                if (otherAddress.equals(candidateAddress)) {
                    return false;
                }

                /*
                 * Netgroup diversity is a selection preference, not an
                 * installation requirement. selectPersistentCandidate() already
                 * prefers another netgroup. If the available address pool cannot
                 * satisfy that preference, keeping a fully handshaken READY peer
                 * is better than throwing it away and leaving the slot empty.
                 */
            }

            slot.connection = newConnection;
            slot.connectionId = newConnection.peer().connectionId();
            slot.connectedAtMicros = newConnection.peer().connectionEstablishedAtMicros();
            slot.chainSync = new PeerChainSyncPolicy();
            slot.headersStarted = false;
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
            PeerCloseReason reason = classifyCloseReason(cause);
            synchronized (monitor) {
                closeReasonCounts.merge(reason, 1L, Long::sum);
            }
            String origin = cause instanceof PeerCloseException peerClose
                    ? peerClose.origin() : "transport/reader";
            log.log(
                    System.Logger.Level.WARNING,
                    "Outbound peer disconnected from slot {0}: {1}; reason={2}; origin={3}; cause={4}; {5}",
                    slot.index, closedConnection.address(), reason, origin,
                    cause == null ? "<none>" : cause.toString(), poolDiagnostics()
            );
        }
    }

    private PeerCloseReason classifyCloseReason(Throwable cause) {
        if (cause instanceof PeerCloseException peerClose) return peerClose.reason();
        Throwable current = cause;
        while (current != null) {
            if (current instanceof EOFException) return PeerCloseReason.REMOTE_EOF;
            current = current.getCause();
        }
        return cause == null ? PeerCloseReason.LOCAL_CLOSE : PeerCloseReason.TRANSPORT_READ_FAILURE;
    }

    private String poolDiagnostics() {
        int connecting;
        synchronized (connectLock) { connecting = connectingAddresses.size(); }
        synchronized (monitor) {
            int active = 0;
            for (Slot candidate : slots) if (candidate.connection != null) active++;
            int target = targetOutboundPeers + targetBlockRelayPeers + (tryExtra || tryBlock || preferredNetwork != null ? 1 : 0);
            return "PEER POOL target=" + target
                    + " active=" + active
                    + " connecting=" + connecting
                    + " empty=" + Math.max(0, target - active - connecting)
                    + " replacements=" + successfulReplacements
                    + " closes=" + closeReasonCounts;
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

    private boolean canConnectSlot(Slot slot) {
        synchronized (monitor) {
            if (!slot.extra) return true;
            if (maximumAutomaticConnections <= targetOutboundPeers + targetBlockRelayPeers) return false;
            if (slot.role == PeerConnectionRole.FULL_RELAY ? (!tryExtra && preferredNetwork == null)
                    : (!tryBlock || tryExtra || preferredNetwork != null)) return false;
            return slots.stream().noneMatch(other -> other != slot && other.extra
                    && (other.connection != null || other.connecting));
        }
    }

    private void startRecoveryHeaders(Slot slot, OutboundPeerConnection connection) {
        if (recoveryView == null) return;
        var work = recoveryView.chainWork(connection.peer());
        if (work == null) return;
        synchronized (monitor) {
            if (stopping || slot.connection != connection) return;
            slot.headersStarted = true;
        }
        try { recoveryView.requestHeaders(connection.peer(), work.locator()); }
        catch (IOException | IllegalStateException failure) { log.log(System.Logger.Level.DEBUG, "Initial recovery headers request failed", failure); }
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

    /** Scheduler entry point; package-visible to exercise recovery without waiting ten minutes. */
    synchronized void checkRecovery(long now) {
        checkRecovery(now, true);
    }

    synchronized void checkRecovery(long now, boolean evictExtra) {
        checkRecoveryAtMicros(Math.multiplyExact(now, 1_000_000), evictExtra);
    }

    synchronized void checkRecoveryAtMicros(long nowMicros, boolean evictExtra) {
        long now = Math.floorDiv(nowMicros, 1_000_000);
        if (recoveryView == null || stopping || !started.get()) return;
        List<OutboundPeerConnection> victims = new ArrayList<>();
        var probes = new java.util.IdentityHashMap<Peer, List<ru.bitcoin.node.common.types.Hash256>>();
        synchronized (monitor) {
            var full = slots.stream().filter(slot -> slot.role == PeerConnectionRole.FULL_RELAY)
                    .filter(slot -> slot.connection != null && slot.connection.peer().isReady()).toList();
            protectedRecoveryPeers.removeIf(peer -> full.stream().noneMatch(slot -> slot.connection.peer() == peer));
            for (Slot slot : full) {
                if (protectedRecoveryPeers.size() >= 4) break;
                if (recoveryView.protectedFromChainSync(slot.connection.peer())) protectedRecoveryPeers.add(slot.connection.peer());
            }
            if (evictExtra && full.size() > targetOutboundPeers) {
                var candidates = full.stream().map(slot -> new ExtraOutboundPeerPolicy.Candidate(
                        slot.connectionId, slot.connection.address().network().bip155Id(), slot.connectedAtMicros,
                        slot.connection.peer().lastNewBlockAnnouncementTime(),
                        protectedRecoveryPeers.contains(slot.connection.peer()),
                        recoveryView.blocksInFlight(slot.connection.peer()))).toList();
                var choice = ExtraOutboundPeerPolicy.evictionCandidate(candidates, nowMicros);
                if (choice.isPresent()) {
                    Slot remove = full.stream().filter(slot -> slot.connectionId == choice.getAsLong()).findFirst().orElseThrow();
                    victims.add(retireExtraPeer(remove));
                    recoveryPolicy.evicted();
                    tryExtra = false;
                }
            }
            var blocks = slots.stream().filter(slot -> slot.role == PeerConnectionRole.BLOCK_RELAY_ONLY)
                    .filter(slot -> slot.connection != null && slot.connection.peer().isReady()).toList();
            if (evictExtra && blocks.size() > targetBlockRelayPeers) {
                var candidates = blocks.stream().map(slot -> new ExtraOutboundPeerPolicy.BlockCandidate(
                        slot.connectionId, slot.connectedAtMicros, slot.connection.peer().lastAcceptedBlockTime(),
                        recoveryView.blocksInFlight(slot.connection.peer()))).toList();
                var choice = ExtraOutboundPeerPolicy.blockEvictionCandidate(candidates, nowMicros);
                if (choice.isPresent()) victims.add(retireExtraPeer(blocks.stream()
                        .filter(slot -> slot.connectionId == choice.getAsLong()).findFirst().orElseThrow()));
            }
            for (Slot slot : slots) {
                if (slot.connection == null || !slot.connection.peer().isReady()) continue;
                Peer peer = slot.connection.peer();
                var work = recoveryView.chainWork(peer);
                if (!slot.headersStarted && work != null) {
                    slot.headersStarted = true;
                    probes.put(peer, work.locator());
                }
                if (!slot.headersStarted) continue;
                var action = slot.chainSync.check(now, protectedRecoveryPeers.contains(peer), work);
                if (action == PeerChainSyncPolicy.Action.REQUEST_HEADERS) probes.put(peer, slot.chainSync.locator());
                if (action == PeerChainSyncPolicy.Action.DISCONNECT) victims.add(slot.connection);
            }
            boolean wasTryingExtra = tryExtra;
            if (evictExtra) tryExtra = recoveryPolicy.check(now, recoveryView.lastTipUpdateSeconds(),
                    recoveryView.blocksInFlight(), recoveryView.enabled());
            if (tryExtra && !wasTryingExtra)
                log.log(System.Logger.Level.INFO, "Potential stale tip: allowing one extra full-relay peer");
            if (recoveryView.directFetch()) blockRotationStarted = true;
            if (targetBlockRelayPeers > 0 && blockRotationStarted && recoveryView.enabled()
                    && !tryExtra && nowMicros > nextBlockProbeMicros
                    && slots.stream().filter(slot -> !slot.extra).allMatch(slot -> slot.connection != null && slot.connection.peer().isReady())
                    && slots.stream().filter(slot -> slot.extra).noneMatch(slot -> slot.connection != null || slot.connecting)) {
                tryBlock = true;
                nextBlockProbeMicros = nowMicros + Math.max(0, blockProbeDelayMicros.getAsLong());
            }
            if (maximumAutomaticConnections > targetOutboundPeers + targetBlockRelayPeers
                    && targetOutboundPeers == 8 && full.size() == 8 && recoveryView.enabled()
                    && !tryExtra && !tryBlock && preferredNetwork == null && nowMicros > nextNetworkProbeMicros
                    && slots.stream().filter(slot -> !slot.extra).allMatch(slot -> slot.connection != null && slot.connection.peer().isReady())
                    && slots.stream().filter(slot -> slot.extra).noneMatch(slot -> slot.connection != null || slot.connecting)) {
                var missing = new ArrayList<>(outboundPeerManager.knownAutomaticNetworks());
                missing.removeIf(network -> full.stream().anyMatch(slot -> slot.connection.address().network() == network));
                java.util.Collections.shuffle(missing, ThreadLocalRandom.current());
                if (!missing.isEmpty()) {
                    preferredNetwork = missing.getFirst();
                    nextNetworkProbeMicros = nowMicros + Math.max(0, networkProbeDelayMicros.getAsLong());
                    log.log(System.Logger.Level.INFO, "Trying an extra full-relay connection to missing network {0}", preferredNetwork);
                }
            }
            monitor.notifyAll();
        }
        probes.forEach((peer, locator) -> {
            if (victims.stream().anyMatch(connection -> connection.peer() == peer)) return;
            try { recoveryView.requestHeaders(peer, locator); }
            catch (IOException | IllegalStateException failure) { log.log(System.Logger.Level.DEBUG, "Chainwork headers request failed", failure); }
        });
        for (var victim : victims) {
            log.log(System.Logger.Level.INFO, "Disconnecting outbound peer selected by recovery policy: {0}", victim.address());
            try { victim.peer().close(new PeerCloseException(PeerCloseReason.LOCAL_RESOURCE_LIMIT,
                    "outbound-peer-recovery", "Outbound peer selected by extra-peer or chainwork eviction")); }
            catch (IOException failure) { log.log(System.Logger.Level.DEBUG, "Extra peer close failed", failure); }
        }
    }

    /** Called under monitor. Preserve supervision and per-connection state on promotion. */
    private OutboundPeerConnection retireExtraPeer(Slot remove) {
        var victim = remove.connection;
        remove.connection = null;
        Slot extra = slots.stream().filter(slot -> slot.extra && slot.role == remove.role).findFirst().orElseThrow();
        if (remove != extra && extra.connection != null) {
            remove.connection = extra.connection;
            remove.connectionId = extra.connectionId;
            remove.connectedAtMicros = extra.connectedAtMicros;
            remove.chainSync = extra.chainSync;
            remove.headersStarted = extra.headersStarted;
            extra.connection = null;
            registerCloseListener(remove, remove.connection);
        }
        protectedRecoveryPeers.remove(victim.peer());
        return victim;
    }

    public int targetBlockRelayPeers() {
        return targetBlockRelayPeers;
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
        List<PeerAddress> anchorsToSave;

        synchronized (monitor) {

            if (stopping) {
                return;
            }

            stopping =
                    true;
            if (recoveryTimer != null) recoveryTimer.shutdownNow();

            anchorsToSave = slots.stream().filter(slot -> slot.role == PeerConnectionRole.BLOCK_RELAY_ONLY)
                    .map(slot -> slot.connection).filter(Objects::nonNull).filter(connection -> connection.peer().isReady())
                    .map(connection -> {
                        var address = connection.address(); var version = connection.peer().evictionVersion();
                        return new PeerAddress(address.network(), address.rawAddress(), address.port(),
                                version == null ? address.services() : version.services());
                    }).limit(BlockRelayAnchorStore.MAX_ANCHORS).toList();

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

        if (anchorStore != null && started.get() && targetBlockRelayPeers > 0) {
            try {
                anchorStore.save(anchorsToSave);
                log.log(System.Logger.Level.INFO, "Saved {0} block relay anchors for clean restart", anchorsToSave.size());
            }
            catch (IOException failure) { log.log(System.Logger.Level.WARNING, "Unable to save block relay anchors", failure); }
        }

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
        private final PeerConnectionRole role;
        private OutboundPeerConnection connection;
        private Thread worker;
        private boolean extra;
        private boolean connecting;
        private PeerChainSyncPolicy chainSync = new PeerChainSyncPolicy();
        private boolean headersStarted;
        private long connectionId;
        private long connectedAtMicros;

        private Slot(int index, PeerConnectionRole role) {
            this.index = index;
            this.role = Objects.requireNonNull(role, "role");
        }
    }
}
