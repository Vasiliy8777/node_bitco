package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Peer implements AutoCloseable {
    private final List<PeerCloseListener> closeListeners =
            new CopyOnWriteArrayList<>();
    private final PeerMessageDispatcher messageDispatcher;
    private final PeerMessageReader messageReader;
    private static final int WTXID_RELAY_VERSION =
            70016;
    private boolean remoteWtxidRelay;

    private boolean remoteWantsAddrV2;

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
        closeListeners.add(
                java.util.Objects.requireNonNull(
                        listener,
                        "listener"
                )
        );
    }

    private void notifyClosed(
            IOException cause
    ) {

        for (PeerCloseListener listener : closeListeners) {

            try {

                listener.onPeerClosed(
                        this,
                        cause
                );

            } catch (RuntimeException ignored) {
                /*
                 * A lifecycle observer must never break
                 * peer shutdown.
                 */
            }
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

        state =
                PeerState.CONNECTED;
    }

    public void handshake()
            throws IOException {

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

        messageReader.start();
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

            case "ping" -> handlePing(message);

            default -> {
                /*
                 * Other post-handshake messages
                 * are currently handled by higher layers
                 * or ignored if unsupported.
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

        /*
         * Explicit close already owns the CLOSED transition.
         */
        if (state == PeerState.CLOSED) {
            return;
        }

        messageDispatcher.failAllPending(
                failure
        );

        try {

            connection.close();

        } catch (IOException closeException) {

            failure.addSuppressed(
                    closeException
            );

        } finally {

            state =
                    PeerState.CLOSED;

            notifyClosed(
                    failure
            );
        }
    }

    @Override
    public void close()
            throws IOException {

        if (state == PeerState.CLOSED) {
            return;
        }

        IOException closedFailure =
                new IOException(
                        "Peer closed"
                );

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

            } finally {

                state =
                        PeerState.CLOSED;

                notifyClosed(
                        closeFailure
                );
            }
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}