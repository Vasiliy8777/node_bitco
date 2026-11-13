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
    private final Map<Peer, PeerConnectionRole> handshakes = new IdentityHashMap<>();

    /** Accounting only: the handshake retains exclusive ownership of reads. */
    public synchronized void trackHandshake(Peer peer, PeerConnectionRole role) {
        Objects.requireNonNull(peer); Objects.requireNonNull(role);
        if (roles.containsKey(peer) || handshakes.putIfAbsent(peer, role) != null) return;
        peer.addCloseListener((closed, cause) -> forgetHandshake(closed));
    }
    private synchronized void forgetHandshake(Peer peer) { handshakes.remove(peer); }
    private final Map<Peer, ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.State> headerSync = new IdentityHashMap<>();
    private final java.util.Set<Peer> headersSending = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Nonblocking ownership across initial/live/availability send attempts. */
    public synchronized boolean tryBeginHeadersSend(Peer peer) {
        return roles.containsKey(peer) && headersSending.add(peer);
    }
    public synchronized void endHeadersSend(Peer peer) { headersSending.remove(peer); }

    private boolean canServeBlocks(Peer peer) {
        return peer.isReady() && (peer.remoteVersion().services() &
                (ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED)) != 0;
    }
    private boolean preferred(Peer peer) {
        return roles.containsKey(peer) && ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.preferred(
                peer.isInboundConnection(), peer.hasPermission(PeerPermission.NOBAN),
                roles.get(peer) == PeerConnectionRole.ADDR_FETCH, canServeBlocks(peer));
    }
    public synchronized int preferredDownloadPeerCount() {
        int count = (int) peers.stream().filter(this::preferred).count();
        for (var entry : handshakes.entrySet()) {
            Peer peer = entry.getKey();
            var version = peer.evictionVersion();
            if (peer.state() != PeerState.CLOSED && version != null
                    && ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.preferred(
                    peer.isInboundConnection(), peer.hasPermission(PeerPermission.NOBAN),
                    entry.getValue() == PeerConnectionRole.ADDR_FETCH,
                    (version.services() & (ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK
                            | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED)) != 0)) count++;
        }
        return count;
    }
    public synchronized boolean canSyncFrom(Peer peer, boolean blocksInFlight) {
        return roles.containsKey(peer) && canServeBlocks(peer)
                && ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.mayFetch(true, preferred(peer),
                        roles.get(peer) == PeerConnectionRole.ADDR_FETCH, preferredDownloadPeerCount(), blocksInFlight);
    }
    public synchronized int headersSyncStartedCount() { return (int) headerSync.values().stream().filter(ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.State::started).count(); }
    public synchronized boolean headersSyncStarted(Peer peer) {
        var state = headerSync.get(peer);
        return state != null && state.started();
    }
    public record HeaderSyncReservation(boolean allowed, boolean newlyStarted) {}
    public synchronized HeaderSyncReservation reserveHeadersSync(Peer peer, long nowMicros, long nowSeconds,
            long bestHeaderSeconds, long spacingSeconds, boolean blocksInFlight) {
        if (!roles.containsKey(peer) || !canServeBlocks(peer)) return new HeaderSyncReservation(false, false);
        var state = headerSync.computeIfAbsent(peer, ignored -> new ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.State());
        if (state.started()) return new HeaderSyncReservation(true, false);
        if (!ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.mayStart(true, false, preferred(peer),
                roles.get(peer) == PeerConnectionRole.ADDR_FETCH, preferredDownloadPeerCount(), blocksInFlight,
                headersSyncStartedCount(), nowSeconds, bestHeaderSeconds)) return new HeaderSyncReservation(false, false);
        state.start(nowMicros, nowSeconds, bestHeaderSeconds, spacingSeconds);
        state.observe(nowSeconds, bestHeaderSeconds);
        return new HeaderSyncReservation(true, true);
    }
    public synchronized void rollbackHeadersSync(Peer peer, HeaderSyncReservation reservation) {
        if (reservation.newlyStarted()) { var state = headerSync.get(peer); if (state != null) state.reset(); }
    }
    public synchronized ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.Action headersSyncTimeout(
            Peer peer, long nowMicros, long nowSeconds, long bestHeaderSeconds) {
        var state = headerSync.get(peer);
        return state == null ? ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.Action.NONE : state.evaluate(
                nowMicros, nowSeconds, bestHeaderSeconds, headersSyncStartedCount(), preferredDownloadPeerCount(),
                preferred(peer), peer.hasPermission(PeerPermission.NOBAN));
    }
    public synchronized void observeHeadersTip(Peer peer, long nowSeconds, long bestHeaderSeconds) {
        var state = headerSync.get(peer); if (state != null) state.observe(nowSeconds, bestHeaderSeconds);
    }
    public synchronized long headersSyncBudgetMicros(Peer peer) {
        var state = headerSync.get(peer); return state == null ? 0 : state.budgetMicros();
    }
    private final java.util.Set<Peer> chainSyncProtected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Call only after a complete validated headers batch proves at least active-tip work. */
    public synchronized boolean protectChainSync(Peer peer) {
        Objects.requireNonNull(peer);
        if (chainSyncProtected.contains(peer)) return true;
        if (roles.get(peer) != PeerConnectionRole.FULL_RELAY || !peer.isReady() || chainSyncProtected.size() >= 4) return false;
        return chainSyncProtected.add(peer);
    }

    public synchronized boolean hasChainSyncProtection(Peer peer) { return chainSyncProtected.contains(peer); }
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
        handshakes.remove(peer);

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

        // Cleanup is unconditional, even if a diagnostic or discouragement
        // callback fails. Socket close happens before this notification.
        synchronized (this) {
            peers.remove(peer);
            roles.remove(peer);
            headerSync.remove(peer);
            headersSending.remove(peer);
            chainSyncProtected.remove(peer);
        }
        if (isProtocolViolation(cause) && !peer.hasPermission(PeerPermission.NOBAN) && !peer.isManualConnection()) {
            java.net.InetSocketAddress remote = peer.remoteAddress();
            if (remote != null && remote.getAddress() != null && !EvictionNetGroup.local(remote.getAddress())) {
                discouragementManager.discourage(remote.getAddress());
            }
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
        handshakes.remove(peer);
        headerSync.remove(peer);
        headersSending.remove(peer);
        chainSyncProtected.remove(peer);
    }

    public synchronized List<Peer> peers() {

        return List.copyOf(
                peers
        );
    }

    public record ManagedPeer(Peer peer, PeerConnectionRole role) { }

    /** Roles are captured with peers so concurrent closure cannot break RPC enumeration. */
    public synchronized List<ManagedPeer> managedPeers() {
        return peers.stream().map(peer -> new ManagedPeer(peer, roles.get(peer))).toList();
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

    public void requestPings() {
        for (Peer peer : readyPeers()) {
            long nonce;
            do {
                nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong();
            } while (nonce == 0L);
            try {
                peer.requestPing(nonce);
            } catch (IOException | IllegalStateException failure) {
                peer.handleReaderFailure(new IOException("RPC ping failed", failure));
            }
        }
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

            var owned = new ArrayList<>(peers);
            for (Peer pending : handshakes.keySet()) if (!owned.contains(pending)) owned.add(pending);
            snapshot = List.copyOf(owned);

            peers.clear();
            roles.clear();
            handshakes.clear();
            headerSync.clear();
            headersSending.clear();
            chainSyncProtected.clear();
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
