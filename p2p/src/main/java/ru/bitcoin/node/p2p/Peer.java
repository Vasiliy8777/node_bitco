package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Peer implements AutoCloseable {
    private final List<PeerMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    public void addMessageListener(PeerMessageListener listener) {
        messageListeners.add(java.util.Objects.requireNonNull(listener));
    }

    public void removeMessageListener(PeerMessageListener listener) {
        messageListeners.remove(listener);
    }
    private final List<PeerCloseListener> closeListeners =
            new CopyOnWriteArrayList<>();
    private final List<PeerInventoryListener> inventoryListeners =
            new CopyOnWriteArrayList<>();
    private final Object lifecycleLock =
            new Object();

    private IOException closeCause;
    private final PeerMessageDispatcher messageDispatcher;
    private final PeerMessageReader messageReader;
    private static final int WTXID_RELAY_VERSION =
            70016;
    private boolean remoteWtxidRelay;

    private boolean remoteWantsAddrV2;

    private volatile boolean inboundConnection;

    private boolean getAddrReceived;

    private boolean localWtxidRelaySent;

    private boolean localSendAddrV2Sent;
    public static final String USER_AGENT =
            "/java-bitcoin-node:0.0.1/";

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private final PeerConnection connection;
    private final long localServices;
    private final int startHeight;
    private final boolean relay;
    private final long localNonce;

    private volatile PeerState state =
            PeerState.DISCONNECTED;

    private VersionMessage remoteVersion;

    private boolean remoteVerackReceived;

    private final Object pingLock = new Object();
    private long pingNonceSent;
    private long pingStartNanos;
    private long lastPingNanos;
    private long lastPingRoundTripNanos = -1L;
    private long minPingRoundTripNanos = -1L;

    public Peer(
            PeerConnection connection,
            long localServices,
            int startHeight,
            boolean relay

    ) {
        this(
                connection,
                localServices,
                startHeight,
                relay,
                RANDOM.nextLong()
        );
    }

    Peer(
            PeerConnection connection,
            long localServices,
            int startHeight,
            boolean relay,
            long localNonce
    ) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "connection must not be null"
            );
        }

        if (startHeight < 0) {
            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        this.connection =
                connection;

        this.localServices =
                localServices;

        this.startHeight =
                startHeight;

        this.relay =
                relay;

        this.localNonce =
                localNonce;
        this.messageDispatcher =
                new PeerMessageDispatcher(
                        this
                );
        this.messageReader =
                new PeerMessageReader(
                        this,
                        messageDispatcher
                );
    }

    public void addCloseListener(
            PeerCloseListener listener
    ) {
        PeerCloseListener checked =
                java.util.Objects.requireNonNull(
                        listener,
                        "listener"
                );

        IOException cause;

        synchronized (lifecycleLock) {

            if (state != PeerState.CLOSED) {

                closeListeners.add(
                        checked
                );

                return;
            }

            cause =
                    closeCause;
        }

        notifyClosed(
                checked,
                cause
        );
    }

    private void notifyClosed(
            List<PeerCloseListener> listeners,
            IOException cause
    ) {

        for (PeerCloseListener listener : listeners) {

            notifyClosed(
                    listener,
                    cause
            );
        }
    }

    private void notifyClosed(
            PeerCloseListener listener,
            IOException cause
    ) {

        try {

            listener.onPeerClosed(
                    this,
                    cause
            );

        } catch (RuntimeException ignored) {

            /*
             * Lifecycle observer must never
             * break peer shutdown.
             */
        }
    }

    public void removeCloseListener(
            PeerCloseListener listener
    ) {
        closeListeners.remove(
                java.util.Objects.requireNonNull(
                        listener,
                        "listener"
                )
        );
    }

    public void connect(
            String host,
            int port
    ) throws IOException {

        if (state != PeerState.DISCONNECTED) {
            throw new IllegalStateException(
                    "Peer has already been started"
            );
        }

        connection.connect(
                host,
                port
        );

        inboundConnection = false;

        state =
                PeerState.CONNECTED;
    }

    public void accept(
            java.net.Socket socket
    ) throws IOException {

        if (state != PeerState.DISCONNECTED) {
            throw new IllegalStateException(
                    "Peer has already been started"
            );
        }

        connection.accept(
                socket
        );

        inboundConnection = true;

        state =
                PeerState.CONNECTED;
    }

    public void handshake()
            throws IOException {
        handshake(true);
    }

    private boolean readerDeferred;

    void handshake(boolean startReader) throws IOException {

        if (state != PeerState.CONNECTED) {
            throw new IllegalStateException(
                    "Peer must be connected before handshake"
            );
        }

        sendVersion();

        while (state != PeerState.READY) {

            Optional<BitcoinMessage> optional =
                    connection.receive();

            if (optional.isEmpty()) {
                throw new IOException(
                        "Peer disconnected during handshake"
                );
            }

            handleHandshakeMessage(
                    optional.orElseThrow()
            );
        }

        /*
         * The connection read timeout protects the handshake
         * from a peer that connects but never completes the
         * protocol negotiation.
         *
         * After READY the connection becomes long-lived.
         * Normal Bitcoin peers may remain silent for longer
         * than the handshake timeout, so socket inactivity
         * must not be interpreted as a disconnect.
         */
        connection.disableReadTimeout();

        readerDeferred = !startReader;
        if (startReader) messageReader.start();
    }

    synchronized void startManagedReader() {
        if (readerDeferred && isReady()) {
            readerDeferred = false;
            messageReader.start();
        }
    }

    private void sendVersion()
            throws IOException {

        InetSocketAddress remote =
                connection.remoteAddress();

        InetSocketAddress local =
                connection.localAddress();

        VersionMessage message =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        localServices,
                        Instant.now().getEpochSecond(),
                        networkAddress(
                                remote,
                                0
                        ),
                        networkAddress(
                                local,
                                localServices
                        ),
                        localNonce,
                        USER_AGENT,
                        startHeight,
                        relay
                );

        connection.send(
                BitcoinMessages.version(
                        message
                )
        );

        state =
                PeerState.VERSION_SENT;
    }

    private void handleHandshakeMessage(
            BitcoinMessage message
    ) throws IOException {

        switch (message.command()) {

            case "version" -> handleVersion(message);

            case "wtxidrelay" -> handleWtxidRelay(message);

            case "sendaddrv2" -> handleSendAddrV2(message);

            case "verack" -> handleVerack(message);

            default -> {
                /*
                 * Other feature-negotiation messages may be
                 * introduced independently.
                 *
                 * Unknown commands are ignored at this layer.
                 */
            }
        }
    }

    private void handleSendAddrV2(
            BitcoinMessage message
    ) throws IOException {

        if (message.payloadLength() != 0) {
            throw new IOException(
                    "sendaddrv2 message must have empty payload"
            );
        }

        if (remoteVersion == null) {
            throw new IOException(
                    "Received sendaddrv2 before version"
            );
        }

        if (remoteVerackReceived) {
            throw new IOException(
                    "Received sendaddrv2 after verack"
            );
        }

        remoteWantsAddrV2 =
                true;
    }

    private void handleWtxidRelay(
            BitcoinMessage message
    ) throws IOException {

        if (message.payloadLength() != 0) {
            throw new IOException(
                    "wtxidrelay message must have empty payload"
            );
        }

        if (remoteVersion == null) {
            throw new IOException(
                    "Received wtxidrelay before version"
            );
        }

        if (remoteVerackReceived) {
            throw new IOException(
                    "Received wtxidrelay after verack"
            );
        }

        int commonVersion =
                Math.min(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        remoteVersion.version()
                );

        if (commonVersion < WTXID_RELAY_VERSION) {
            /*
             * Bitcoin Core ignores WTXIDRELAY when the
             * negotiated protocol version is too old.
             */
            return;
        }

        /*
         * Bitcoin Core tolerates duplicate WTXIDRELAY
         * messages before VERACK.
         */
        remoteWtxidRelay =
                true;
    }

    private void handleVersion(
            BitcoinMessage message
    ) throws IOException {

        if (remoteVersion != null) {
            throw new IOException(
                    "Peer sent duplicate version message"
            );
        }

        VersionMessage version =
                BitcoinMessages.decodeVersion(
                        message
                );

        if (version.nonce() == localNonce) {
            throw new IOException(
                    "Self connection detected"
            );
        }

        remoteVersion =
                version;

        state =
                PeerState.VERSION_RECEIVED;

        sendFeatureNegotiation(
                version
        );

        connection.send(
                BitcoinMessages.verack()
        );

        state =
                PeerState.VERACK_SENT;

        updateReadyState();
    }

    private void sendFeatureNegotiation(
            VersionMessage version
    ) throws IOException {

        int commonVersion =
                Math.min(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        version.version()
                );

        if (commonVersion >= WTXID_RELAY_VERSION) {

            connection.send(
                    BitcoinMessages.wtxidRelay()
            );

            localWtxidRelaySent =
                    true;

            connection.send(
                    BitcoinMessages.sendAddrV2()
            );

            localSendAddrV2Sent =
                    true;
        }
    }

    private void handleVerack(
            BitcoinMessage message
    ) throws IOException {

        if (message.payloadLength() != 0) {
            throw new IOException(
                    "verack message must have empty payload"
            );
        }

        if (remoteVersion == null) {
            throw new IOException(
                    "Received verack before version"
            );
        }

        if (remoteVerackReceived) {
            throw new IOException(
                    "Peer sent duplicate verack message"
            );
        }

        remoteVerackReceived =
                true;

        updateReadyState();
    }

    private void updateReadyState() {
        if (remoteVersion != null
                && remoteVerackReceived) {

            state =
                    PeerState.READY;
        }
    }

    private static NetworkAddress networkAddress(
            InetSocketAddress socketAddress,
            long services
    ) {
        InetAddress address =
                socketAddress.getAddress();

        if (address == null) {
            return NetworkAddress.unspecified();
        }

        return NetworkAddress.fromIp(
                services,
                address,
                socketAddress.getPort()
        );
    }

    public PeerState state() {
        return state;
    }

    public boolean isReady() {
        return state == PeerState.READY;
    }

    public InetSocketAddress remoteAddress() {

        return connection.remoteAddress();
    }

    public VersionMessage remoteVersion() {
        if (remoteVersion == null) {
            throw new IllegalStateException(
                    "Remote version has not been received"
            );
        }

        return remoteVersion;
    }

    public void handleMessage(
            BitcoinMessage message
    ) throws IOException {

        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        if (!isReady()) {
            throw new IllegalStateException(
                    "Peer handshake is not complete"
            );
        }

        switch (message.command()) {

            case "ping" ->
                    handlePing(
                            message
                    );

            case "pong" ->
                    handlePong(
                            message
                    );

            case "inv" ->
                    handleInv(
                            message
                    );

            default -> {
                /*
                 * Application handlers observe other unsolicited messages below.
                 */
            }
        }
        for (PeerMessageListener listener :
                messageListeners) {

            try {

                listener.onMessage(
                        this,
                        message
                );

            } catch (PeerProtocolException exception) {

                throw new IOException(
                        "Peer protocol handler rejected "
                                + message.command()
                                + " message",
                        exception
                );

            } catch (RuntimeException ignored) {

                /*
                 * Ordinary observers are isolated from the
                 * reader thread. One broken observer must not
                 * suppress subsequent observers or disconnect
                 * an otherwise valid peer.
                 */
            }
        }
    }

    private void handlePong(
            BitcoinMessage message
    ) throws IOException {

        final PongMessage pong;

        try {
            pong = BitcoinMessages.decodePong(message);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid pong message", exception);
        }

        long now = System.nanoTime();

        synchronized (pingLock) {
            if (pingNonceSent == 0L || pong.nonce() != pingNonceSent) {
                return;
            }

            long roundTrip = now - pingStartNanos;
            if (roundTrip >= 0L) {
                lastPingRoundTripNanos = roundTrip;
                if (minPingRoundTripNanos < 0L || roundTrip < minPingRoundTripNanos) {
                    minPingRoundTripNanos = roundTrip;
                }
            }

            pingNonceSent = 0L;
        }
    }

    boolean sendPingIfDue(
            long nowNanos,
            long intervalNanos,
            long nonce
    ) throws IOException {
        if (nonce == 0L) {
            throw new IllegalArgumentException("ping nonce must not be zero");
        }

        synchronized (pingLock) {
            if (!isReady() || pingNonceSent != 0L) {
                return false;
            }
            if (lastPingNanos != 0L && nowNanos - lastPingNanos < intervalNanos) {
                return false;
            }

            connection.send(BitcoinMessages.ping(new PingMessage(nonce)));
            pingNonceSent = nonce;
            pingStartNanos = nowNanos;
            lastPingNanos = nowNanos;
            return true;
        }
    }

    boolean pingTimedOut(long nowNanos, long timeoutNanos) {
        synchronized (pingLock) {
            return pingNonceSent != 0L
                    && nowNanos - pingStartNanos > timeoutNanos;
        }
    }

    public Optional<Duration> lastPingRoundTrip() {
        synchronized (pingLock) {
            return lastPingRoundTripNanos < 0L
                    ? Optional.empty()
                    : Optional.of(Duration.ofNanos(lastPingRoundTripNanos));
        }
    }

    public Optional<Duration> minPingRoundTrip() {
        synchronized (pingLock) {
            return minPingRoundTripNanos < 0L
                    ? Optional.empty()
                    : Optional.of(Duration.ofNanos(minPingRoundTripNanos));
        }
    }

    public boolean hasOutstandingPing() {
        synchronized (pingLock) {
            return pingNonceSent != 0L;
        }
    }

    private void handleInv(
            BitcoinMessage message
    ) throws IOException {

        final InvMessage inventory;

        try {

            inventory =
                    BitcoinMessages.decodeInv(
                            message
                    );

        } catch (IllegalArgumentException exception) {

            throw new IOException(
                    "Invalid inv message",
                    exception
            );
        }

        for (PeerInventoryListener listener :
                inventoryListeners) {

            try {

                listener.onInventory(
                        this,
                        inventory
                );

            } catch (RuntimeException ignored) {

                /*
                 * An application-level inventory observer
                 * must not terminate the peer reader thread.
                 */
            }
        }
    }

    private void handlePing(
            BitcoinMessage message
    ) throws IOException {

        final PingMessage ping;

        try {
            ping =
                    BitcoinMessages.decodePing(
                            message
                    );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Invalid ping message",
                    exception
            );
        }

        connection.send(
                BitcoinMessages.pong(
                        new PongMessage(
                                ping.nonce()
                        )
                )
        );
    }

    public void send(
            BitcoinMessage message
    ) throws IOException {

        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        if (!isReady()) {
            throw new IllegalStateException(
                    "Peer handshake is not complete"
            );
        }

        connection.send(
                message
        );
    }

    public void addInventoryListener(
            PeerInventoryListener listener
    ) {
        inventoryListeners.add(
                java.util.Objects.requireNonNull(
                        listener,
                        "listener"
                )
        );
    }

    public void removeInventoryListener(
            PeerInventoryListener listener
    ) {
        inventoryListeners.remove(
                java.util.Objects.requireNonNull(
                        listener,
                        "listener"
                )
        );
    }

    public Optional<BitcoinMessage> receive()
            throws IOException {

        if (!isReady()) {
            throw new IllegalStateException(
                    "Peer handshake is not complete"
            );
        }

        return connection.receive();
    }

    public PeerMessageDispatcher messageDispatcher() {
        return messageDispatcher;
    }

    public PeerMessageReader messageReader() {
        return messageReader;
    }

    public long localNonce() {
        return localNonce;
    }

    public boolean remoteWtxidRelay() {
        return remoteWtxidRelay;
    }

    public boolean isInboundConnection() {
        return inboundConnection;
    }

    /**
     * Marks the first GETADDR received from this peer.
     *
     * @return true only for the first GETADDR on this connection.
     */
    public synchronized boolean markGetAddrReceived() {

        if (getAddrReceived) {
            return false;
        }

        getAddrReceived = true;

        return true;
    }

    public synchronized boolean getAddrReceived() {
        return getAddrReceived;
    }

    public boolean remoteWantsAddrV2() {
        return remoteWantsAddrV2;
    }

    public boolean localWtxidRelaySent() {
        return localWtxidRelaySent;
    }

    public boolean localSendAddrV2Sent() {
        return localSendAddrV2Sent;
    }

    void handleReaderFailure(
            IOException failure
    ) {

        if (failure == null) {

            throw new IllegalArgumentException(
                    "failure must not be null"
            );
        }

        List<PeerCloseListener> listeners;

        synchronized (lifecycleLock) {

            /*
             * Another thread may already own
             * the CLOSED transition.
             *
             * Only the first thread is allowed
             * to perform shutdown notification.
             */
            if (state == PeerState.CLOSED) {
                return;
            }

            state =
                    PeerState.CLOSED;

            closeCause =
                    failure;

            listeners =
                    List.copyOf(
                            closeListeners
                    );
        }

        messageDispatcher.failAllPending(
                failure
        );

        try {

            messageReader.close();

            connection.close();

        } catch (IOException closeException) {

            failure.addSuppressed(
                    closeException
            );

        } finally {

            notifyClosed(
                    listeners,
                    failure
            );
        }
    }

    @Override
    public void close()
            throws IOException {

        IOException closedFailure =
                new IOException(
                        "Peer closed"
                );

        List<PeerCloseListener> listeners;

        synchronized (lifecycleLock) {

            /*
             * Exactly one thread owns
             * the CLOSED transition.
             */
            if (state == PeerState.CLOSED) {
                return;
            }

            state =
                    PeerState.CLOSED;

            closeCause =
                    closedFailure;

            listeners =
                    List.copyOf(
                            closeListeners
                    );
        }

        messageDispatcher.failAllPending(
                closedFailure
        );

        IOException closeFailure =
                null;

        try {

            messageReader.close();

        } finally {

            try {

                connection.close();

            } catch (IOException exception) {

                closeFailure =
                        exception;

                closedFailure.addSuppressed(
                        exception
                );

            } finally {

                notifyClosed(
                        listeners,
                        closedFailure
                );
            }
        }

        if (closeFailure != null) {

            throw closeFailure;
        }
    }
}
