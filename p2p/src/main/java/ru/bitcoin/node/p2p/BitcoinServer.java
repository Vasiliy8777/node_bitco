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
            190;

    private final NetworkParameters networkParameters;
    private final PeerManager peerManager;
    private final PeerDiscouragementManager discouragementManager;
    private PeerPermissionPolicy permissionPolicy = PeerPermissionPolicy.none();

    public synchronized void permissionPolicy(PeerPermissionPolicy policy) {
        if (running) throw new IllegalStateException("Set inbound permissions before starting the server");
        permissionPolicy = Objects.requireNonNull(policy);
    }

    private final long localServices;
    private final boolean relay;
    private final int maxInboundConnections;
    private final boolean v2Transport;
    private final int maxInboundFullRelay;
    private final Set<Peer> inboundFullRelay = new HashSet<>();
    private record InboundMetadata(long id, long connected, java.net.InetAddress address, long netGroup, boolean preferEvict) { }
    private final java.util.Map<Peer, InboundMetadata> inboundMetadata = new java.util.IdentityHashMap<>();
    private final Set<Peer> evicting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final long[] evictionKey = new java.security.SecureRandom().longs(2).toArray();
    private long nextInboundId;
    private volatile java.util.function.BooleanSupplier initialBlockDownload = () -> false;

    public void initialBlockDownload(java.util.function.BooleanSupplier supplier) {
        initialBlockDownload = Objects.requireNonNull(supplier);
    }

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
                DEFAULT_MAX_INBOUND_CONNECTIONS,
                peerManager.discouragementManager(),
                false, 50
        );
    }

    public BitcoinServer(
            NetworkParameters networkParameters,
            PeerManager peerManager,
            long localServices,
            boolean relay,
            int maxInboundConnections
    ) {
        this(networkParameters, peerManager, localServices, relay, maxInboundConnections,
                peerManager.discouragementManager(), false);
    }

    public BitcoinServer(
            NetworkParameters networkParameters,
            PeerManager peerManager,
            long localServices,
            boolean relay,
            int maxInboundConnections,
            PeerDiscouragementManager discouragementManager
    ) {
        this(networkParameters, peerManager, localServices, relay, maxInboundConnections, discouragementManager, false);
    }

    public BitcoinServer(NetworkParameters networkParameters, PeerManager peerManager, long localServices,
                         boolean relay, int maxInboundConnections, PeerDiscouragementManager discouragementManager,
                         boolean v2Transport) {
        this(networkParameters, peerManager, localServices, relay, maxInboundConnections,
                discouragementManager, v2Transport, 100);
    }

    public BitcoinServer(NetworkParameters networkParameters, PeerManager peerManager, long localServices,
                         boolean relay, int maxInboundConnections, PeerDiscouragementManager discouragementManager,
                         boolean v2Transport, int inboundRelayPercent) {

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

        this.discouragementManager = Objects.requireNonNull(
                discouragementManager, "discouragementManager");

        if (maxInboundConnections < 0 || maxInboundConnections > 2000
                || inboundRelayPercent < 0 || inboundRelayPercent > 100) {
            throw new IllegalArgumentException(
                    "Invalid inbound connection count or relay percentage"
            );
        }

        this.localServices =
                localServices;

        this.relay =
                relay;

        this.maxInboundConnections =
                maxInboundConnections;
        this.v2Transport = v2Transport;
        this.maxInboundFullRelay = maxInboundConnections * inboundRelayPercent / 100;

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

                PeerAccess access = permissionPolicy.accessFor(socket.getInetAddress());
                boolean noBan = access.permissions().contains(PeerPermission.NOBAN);
                boolean discouraged = !noBan && socket.getInetAddress() != null && discouragementManager.isDiscouraged(socket.getInetAddress());
                if (!noBan && socket.getInetAddress() != null
                        && (peerManager.banManager().isBanned(socket.getInetAddress())
                        || (discouraged && inboundConnectionCount.get() + 1 >= maxInboundConnections))) {
                    closeQuietly(socket);
                    socket = null;
                    continue;
                }

                if (!reserveInboundSlot() && (!evictInbound(false, null) || !reserveInboundSlot())) {

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
                                    acceptedSocket, discouraged, access
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
            Socket socket, boolean discouraged, PeerAccess access
    ) {

        Peer peer =
                new Peer(
                        new PeerConnection(
                                networkParameters
                        ),
                        localServices,
                        startHeight,
                        relay || access.permissions().contains(PeerPermission.RELAY),
                        access
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
            byte[] group = EvictionNetGroup.group(socket.getInetAddress());
            byte[] keyedInput = java.nio.ByteBuffer.allocate(8 + group.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putLong(0x6c0edd8036ef4036L).put(group).array();
            long keyedGroup = ru.bitcoin.node.crypto.hash.SipHash24.hash(evictionKey[0], evictionKey[1], keyedInput);
            inboundMetadata.put(peer, new InboundMetadata(nextInboundId++, System.currentTimeMillis(), socket.getInetAddress(), keyedGroup, discouraged));
        }

        peer.addCloseListener(
                (closedPeer, cause) ->
                        removeInboundPeer(
                                closedPeer
                        )
        );

        try {

            peer.acceptNegotiated(socket, v2Transport);

            peerManager.trackHandshake(peer, PeerConnectionRole.INBOUND);
            peer.handshake(false);

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

            if (!admitRelayRole(peer)) { peer.close(); return; }

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
            // Eviction can race before PeerConnection has attached the accepted socket.
            closeQuietly(socket);
        }
    }

    private boolean admitRelayRole(Peer peer) {
        while (running && peer.isReady()) {
            synchronized (inboundPeers) {
                if (!inboundPeers.contains(peer) || evicting.contains(peer)) return false;
                boolean relays = peer.remoteVersion().relay();
                if (!relays || inboundFullRelay.size() < maxInboundFullRelay) {
                    if (relays) inboundFullRelay.add(peer);
                    return true;
                }
            }
            // Only transaction-relaying peers are candidates for the full-relay cap.
            // The new connection is protected; if all existing peers are protected, reject it.
            if (!evictInbound(true, peer)) return false;
        }
        return false;
    }

    private boolean evictInbound(boolean transactionRelayOnly, Peer protectedPeer) {
        if (!running) return false;
        boolean allowLimited = !initialBlockDownload.getAsBoolean();
        Peer selected = null;
        synchronized (inboundPeers) {
            var candidates = new java.util.ArrayList<InboundPeerEviction.Candidate>();
            for (var entry : inboundMetadata.entrySet()) {
                var peer = entry.getKey(); var metadata = entry.getValue();
                if (peer == protectedPeer || evicting.contains(peer) || peer.state() == PeerState.CLOSED) continue;
                var version = peer.evictionVersion();
                boolean relays = version != null && version.relay();
                if (transactionRelayOnly && !relays) continue;
                long services = version == null ? 0 : version.services();
                boolean relevant = (services & VersionMessage.NODE_WITNESS) != 0
                        && ((services & VersionMessage.NODE_NETWORK) != 0
                        || (allowLimited && (services & VersionMessage.NODE_NETWORK_LIMITED) != 0));
                var network = metadata.address().getAddress().length == 4
                        ? InboundPeerEviction.Network.IPV4 : InboundPeerEviction.Network.IPV6;
                candidates.add(new InboundPeerEviction.Candidate(metadata.id(), metadata.connected(),
                        peer.minPingRoundTrip().map(java.time.Duration::toNanos).orElse(Long.MAX_VALUE),
                        peer.lastAcceptedBlockTime(), peer.lastAcceptedTransactionTime(), relevant, relays, false,
                        metadata.netGroup(), metadata.preferEvict(),
                        EvictionNetGroup.local(metadata.address()), network, peer.hasPermission(PeerPermission.NOBAN), true));
            }
            var id = InboundPeerEviction.select(candidates);
            if (id.isEmpty()) return false;
            for (var entry : inboundMetadata.entrySet()) if (entry.getValue().id() == id.getAsLong()) {
                selected = entry.getKey(); evicting.add(selected); break;
            }
        }
        if (selected == null) return false;
        // Closing invokes listeners and may take other locks; never do it under inboundPeers.
        try {
            selected.close(new PeerCloseException(PeerCloseReason.LOCAL_RESOURCE_LIMIT,
                    "inbound-eviction", "Inbound peer selected for eviction by Core 32.1 protection policy"));
        } catch (IOException failure) {
            System.getLogger(BitcoinServer.class.getName()).log(System.Logger.Level.DEBUG, "Evicted peer close failed", failure);
        } finally { removeInboundPeer(selected); }
        return true;
    }

    private void removeInboundPeer(
            Peer peer
    ) {

        boolean removed;

        synchronized (inboundPeers) {
            inboundFullRelay.remove(peer);
            inboundMetadata.remove(peer);
            evicting.remove(peer);

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
