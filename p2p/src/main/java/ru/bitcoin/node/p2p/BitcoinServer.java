package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BitcoinServer
        implements AutoCloseable {

    private final AtomicInteger inboundConnectionCount =
            new AtomicInteger();

    public static final int DEFAULT_MAX_INBOUND_CONNECTIONS =
            32;

    private final NetworkParameters networkParameters;
    private final PeerManager peerManager;

    private final long localServices;
    private final boolean relay;
    private final int maxInboundConnections;

    private final ExecutorService handshakeExecutor;

    private final Set<Peer> inboundPeers =
            new HashSet<>();

    private final Object lifecycleLock =
            new Object();

    private volatile boolean running;

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    private volatile int startHeight;

    public BitcoinServer(
            NetworkParameters networkParameters,
            PeerManager peerManager
    ) {
        this(
                networkParameters,
                peerManager,
                VersionMessage.DEFAULT_SERVICES,
                true,
                DEFAULT_MAX_INBOUND_CONNECTIONS
        );
    }

    public BitcoinServer(
            NetworkParameters networkParameters,
            PeerManager peerManager,
            long localServices,
            boolean relay,
            int maxInboundConnections
    ) {

        this.networkParameters =
                Objects.requireNonNull(
                        networkParameters,
                        "networkParameters"
                );

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        if (maxInboundConnections <= 0) {
            throw new IllegalArgumentException(
                    "maxInboundConnections must be positive"
            );
        }

        this.localServices =
                localServices;

        this.relay =
                relay;

        this.maxInboundConnections =
                maxInboundConnections;

        this.handshakeExecutor =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual()
                                .name(
                                        "bitcoin-inbound-handshake-",
                                        0
                                )
                                .factory()
                );
    }

    public void start(
            int port,
            int startHeight
    ) throws IOException {

        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port
            );
        }

        if (startHeight < 0) {
            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        ServerSocket newServerSocket =
                new ServerSocket();

        try {

            newServerSocket.setReuseAddress(
                    true
            );

            newServerSocket.bind(
                    new java.net.InetSocketAddress(
                            port
                    )
            );

        } catch (IOException | RuntimeException exception) {

            try {
                newServerSocket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(
                        closeException
                );
            }

            throw exception;
        }

        synchronized (lifecycleLock) {

            if (running) {

                try {
                    newServerSocket.close();
                } catch (IOException ignored) {
                    // Preserve the lifecycle exception.
                }

                throw new IllegalStateException(
                        "Bitcoin server is already running"
                );
            }

            this.startHeight =
                    startHeight;

            this.serverSocket =
                    newServerSocket;

            this.running =
                    true;

            Thread thread =
                    new Thread(
                            this::acceptLoop,
                            "bitcoin-p2p-accept"
                    );

            thread.setDaemon(
                    true
            );

            this.acceptThread =
                    thread;

            thread.start();
        }
    }

    private void acceptLoop() {

        while (running) {

            Socket socket =
                    null;

            try {

                ServerSocket currentServerSocket =
                        serverSocket;

                if (currentServerSocket == null) {
                    return;
                }

                socket =
                        currentServerSocket.accept();

                if (!reserveInboundSlot()) {

                    closeQuietly(
                            socket
                    );

                    continue;
                }

                Socket acceptedSocket =
                        socket;

                socket =
                        null;

                try {

                    handshakeExecutor.execute(
                            () -> handleInboundConnection(
                                    acceptedSocket
                            )
                    );

                } catch (RejectedExecutionException exception) {

                    releaseInboundSlot();

                    closeQuietly(
                            acceptedSocket
                    );

                    if (running) {
                        throw exception;
                    }
                }

            } catch (SocketException exception) {

                closeQuietly(
                        socket
                );

                /*
                 * Closing ServerSocket is the normal way
                 * to wake a thread blocked in accept().
                 */
                if (!running) {
                    return;
                }

            } catch (IOException exception) {

                closeQuietly(
                        socket
                );

                if (!running) {
                    return;
                }

            } catch (RuntimeException exception) {

                closeQuietly(
                        socket
                );

                if (!running) {
                    return;
                }
            }
        }
    }

    private boolean reserveInboundSlot() {

        while (true) {

            int current =
                    inboundConnectionCount.get();

            if (current >= maxInboundConnections) {
                return false;
            }

            if (inboundConnectionCount.compareAndSet(
                    current,
                    current + 1
            )) {
                return true;
            }
        }
    }

    private void handleInboundConnection(
            Socket socket
    ) {

        Peer peer =
                new Peer(
                        new PeerConnection(
                                networkParameters
                        ),
                        localServices,
                        startHeight,
                        relay
                );

        synchronized (inboundPeers) {

            if (!running) {

                releaseInboundSlot();

                closeQuietly(
                        socket
                );

                return;
            }

            inboundPeers.add(
                    peer
            );
        }

        peer.addCloseListener(
                (closedPeer, cause) ->
                        removeInboundPeer(
                                closedPeer
                        )
        );

        try {

            peer.accept(
                    socket
            );

            peer.handshake();

            if (!peer.isReady()) {
                throw new IOException(
                        "Inbound peer handshake completed "
                                + "without READY state"
                );
            }

            if (!running) {

                peer.close();

                return;
            }

            peerManager.add(
                    peer
            );

        } catch (IOException | RuntimeException exception) {

            try {

                peer.close();

            } catch (IOException closeException) {

                exception.addSuppressed(
                        closeException
                );
            }

            removeInboundPeer(
                    peer
            );
        }
    }

    private void removeInboundPeer(
            Peer peer
    ) {

        boolean removed;

        synchronized (inboundPeers) {

            removed =
                    inboundPeers.remove(
                            peer
                    );
        }

        if (removed) {
            releaseInboundSlot();
        }
    }

    private void releaseInboundSlot() {

        int remaining =
                inboundConnectionCount.decrementAndGet();

        if (remaining < 0) {

            inboundConnectionCount.set(
                    0
            );

            throw new IllegalStateException(
                    "Inbound connection count became negative"
            );
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int localPort() {

        ServerSocket current =
                serverSocket;

        if (current == null
                || current.isClosed()) {

            throw new IllegalStateException(
                    "Bitcoin server is not running"
            );
        }

        return current.getLocalPort();
    }

    public int inboundPeerCount() {
        return inboundConnectionCount.get();
    }

    @Override
    public void close()
            throws IOException {

        ServerSocket socketToClose;
        Thread threadToJoin;
        Set<Peer> peersToClose;

        synchronized (lifecycleLock) {

            if (!running
                    && serverSocket == null) {

                return;
            }

            running =
                    false;

            socketToClose =
                    serverSocket;

            serverSocket =
                    null;

            threadToJoin =
                    acceptThread;

            acceptThread =
                    null;
        }

        IOException failure =
                null;

        if (socketToClose != null) {

            try {

                socketToClose.close();

            } catch (IOException exception) {

                failure =
                        exception;
            }
        }

        if (threadToJoin != null
                && threadToJoin != Thread.currentThread()) {

            try {

                threadToJoin.join(
                        5_000
                );

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                IOException interrupted =
                        new IOException(
                                "Interrupted while stopping Bitcoin server",
                                exception
                        );

                if (failure == null) {
                    failure = interrupted;
                } else {
                    failure.addSuppressed(
                            interrupted
                    );
                }
            }
        }

        handshakeExecutor.shutdownNow();

        synchronized (inboundPeers) {

            peersToClose =
                    Set.copyOf(
                            inboundPeers
                    );

            inboundPeers.clear();
        }

        for (Peer peer : peersToClose) {

            try {

                peer.close();

            } catch (IOException exception) {

                if (failure == null) {

                    failure =
                            new IOException(
                                    "Failed to close one or more inbound peers"
                            );
                }

                failure.addSuppressed(
                        exception
                );
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static void closeQuietly(
            Socket socket
    ) {

        if (socket == null) {
            return;
        }

        try {

            socket.close();

        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}