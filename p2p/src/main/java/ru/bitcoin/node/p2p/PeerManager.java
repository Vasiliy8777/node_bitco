package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Objects;

public final class PeerManager
        implements AutoCloseable {

    private final List<Peer> peers =
            new ArrayList<>();
    private final List<java.util.function.Consumer<Peer>> listeners = new ArrayList<>();
    private final Map<Peer, PeerConnectionRole> roles = new IdentityHashMap<>();
    private final PeerDiscouragementManager discouragementManager;
    private final PeerBanManager banManager;

    public PeerManager() {
        this(new PeerDiscouragementManager(), new PeerBanManager());
    }

    public PeerManager(PeerDiscouragementManager discouragementManager) {
        this(discouragementManager, new PeerBanManager());
    }

    public PeerManager(PeerDiscouragementManager discouragementManager, PeerBanManager banManager) {
        this.discouragementManager = Objects.requireNonNull(discouragementManager, "discouragementManager");
        this.banManager = Objects.requireNonNull(banManager, "banManager");
    }

    /** Callbacks only attach nonblocking observers; called under the manager lock. */
    public synchronized void addPeerListener(java.util.function.Consumer<Peer> listener) {
        listeners.add(Objects.requireNonNull(listener));
        peers.forEach(listener);
    }

    public synchronized void removePeerListener(java.util.function.Consumer<Peer> listener) {
        listeners.remove(listener);
    }

    public synchronized void add(
            Peer peer
    ) {
        add(peer, peer.isInboundConnection() ? PeerConnectionRole.INBOUND : PeerConnectionRole.FULL_RELAY);
    }

    public synchronized void add(Peer peer, PeerConnectionRole role) {

        Objects.requireNonNull(
                peer,
                "peer"
        );
        Objects.requireNonNull(role, "role");

        if (peers.contains(peer)) {
            return;
        }

        /*
         * IMPORTANT:
         *
         * Add the peer before registering
         * the close listener.
         *
         * Peer.addCloseListener() immediately
         * reports an already CLOSED peer.
         *
         * Therefore this ordering closes the race:
         *
         * peer closes
         *      ↓
         * PeerManager.add(peer)
         *      ↓
         * add to collection
         *      ↓
         * addCloseListener()
         *      ↓
         * immediate close callback
         *      ↓
         * peer removed again
         */
        peers.add(
                peer
        );
        roles.put(peer, role);

        peer.addCloseListener(
                this::onPeerClosed
        );
        if (peers.contains(peer)) {
            listeners.forEach(listener -> listener.accept(peer));
            peer.startManagedReader();
        }
    }

    private void onPeerClosed(
            Peer peer,
            IOException cause
    ) {

        if (isProtocolViolation(cause)) {
            java.net.InetSocketAddress remote = peer.remoteAddress();
            if (remote != null && remote.getAddress() != null) {
                discouragementManager.discourage(remote.getAddress());
            }
        }

        synchronized (this) {
            peers.remove(peer);
            roles.remove(peer);
        }
    }

    private static boolean isProtocolViolation(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof PeerProtocolException) return true;
        }
        return false;
    }

    public PeerDiscouragementManager discouragementManager() {
        return discouragementManager;
    }

    public PeerBanManager banManager() { return banManager; }

    public void disconnect(java.net.InetAddress address) {
        for (Peer peer : peers()) {
            java.net.InetSocketAddress remote = peer.remoteAddress();
            if (remote != null && address.equals(remote.getAddress())) {
                try { peer.close(); } catch (IOException ignored) { }
            }
        }
    }

    public synchronized void remove(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        peers.remove(
                peer
        );
        roles.remove(peer);
    }

    public synchronized List<Peer> peers() {

        return List.copyOf(
                peers
        );
    }

    public synchronized PeerConnectionRole roleOf(Peer peer) {
        Objects.requireNonNull(peer, "peer");
        PeerConnectionRole role = roles.get(peer);
        if (role == null) throw new IllegalArgumentException("Peer is not managed");
        return role;
    }

    public synchronized boolean hasRole(Peer peer, PeerConnectionRole role) {
        return roles.get(peer) == role;
    }

    public synchronized List<Peer> readyPeers() {

        return peers.stream()
                .filter(Peer::isReady)
                .toList();
    }

    public synchronized int size() {
        return peers.size();
    }

    public synchronized boolean isEmpty() {
        return peers.isEmpty();
    }

    @Override
    public void close()
            throws IOException {

        List<Peer> snapshot;

        synchronized (this) {

            snapshot =
                    List.copyOf(
                            peers
                    );

            peers.clear();
            roles.clear();
        }

        IOException failure =
                null;

        for (Peer peer : snapshot) {

            try {

                peer.close();

            } catch (IOException exception) {

                if (failure == null) {

                    failure =
                            new IOException(
                                    "Failed to close one or more peers"
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
}
