package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnectionRole;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.message.AddrEntry;
import ru.bitcoin.node.p2p.message.AddrMessage;
import ru.bitcoin.node.p2p.message.AddrV2Entry;
import ru.bitcoin.node.p2p.message.AddrV2Message;
import ru.bitcoin.node.p2p.message.BitcoinMessages;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Core-style per-peer address gossip state, selection, known suppression and trickled sending. */
public final class PeerAddressRelayManager implements AutoCloseable {
    static final long AVG_ADDRESS_BROADCAST_NANOS = TimeUnit.SECONDS.toNanos(30);
    static final long ROTATION_NANOS = TimeUnit.HOURS.toNanos(24);
    static final int MAX_RELAY_DESTINATIONS = 2;

    private final PeerManager peerManager;
    private final LongSupplier nanoTime;
    private final Map<Peer, PeerAddressRelayState> states = new WeakHashMap<>();
    private final ScheduledExecutorService scheduler;

    public PeerAddressRelayManager(PeerManager peerManager) {
        this(peerManager, System::nanoTime, true);
    }

    PeerAddressRelayManager(PeerManager peerManager, LongSupplier nanoTime, boolean startScheduler) {
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (startScheduler) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "bitcoin-addr-relay");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::flushDueSafely, 1, 1, TimeUnit.SECONDS);
        } else scheduler = null;
    }

    public boolean setupAddressRelay(Peer peer) {
        Objects.requireNonNull(peer, "peer");
        if (peerManager.hasRole(peer, PeerConnectionRole.BLOCK_RELAY_ONLY)) return false;
        return state(peer).enable() || state(peer).enabled();
    }

    public boolean addressRelayEnabled(Peer peer) {
        synchronized (states) {
            PeerAddressRelayState state = states.get(peer);
            return state != null && state.enabled();
        }
    }

    public void markKnown(Peer peer, PeerAddress address) {
        if (setupAddressRelay(peer)) state(peer).markKnown(address);
    }

    /** Relay a fresh unsolicited address to at most two deterministic eligible peers, never back to origin. */
    public void relay(Peer origin, PeerAddress address) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(address, "address");
        long rotation = Math.floorDiv(nanoTime.getAsLong(), ROTATION_NANOS);
        List<Peer> eligible = peerManager.peers().stream()
                .filter(peer -> peer != origin)
                .filter(Peer::isReady)
                .filter(this::addressRelayEnabled)
                .filter(peer -> peer.remoteWantsAddrV2() || address.isLegacyAddrCompatible())
                .filter(peer -> !state(peer).knows(address))
                .sorted(Comparator.comparingLong(
                        (Peer peer) -> relayScore(peer, address, rotation)
                ).reversed())
                .limit(MAX_RELAY_DESTINATIONS)
                .toList();
        for (Peer peer : eligible) state(peer).queue(address);
    }

    void flushDue() {
        long now = nanoTime.getAsLong();
        for (Peer peer : peerManager.peers()) {
            if (!peer.isReady() || !addressRelayEnabled(peer)) continue;
            PeerAddressRelayState state = state(peer);
            if (state.nextSendNanos() == 0L) state.nextSendNanos(now + nextDelayNanos(peer, now));
            if (now < state.nextSendNanos()) continue;
            state.nextSendNanos(now + nextDelayNanos(peer, now));
            List<PeerAddress> addresses = state.drainCompatible(peer.remoteWantsAddrV2());
            if (addresses.isEmpty()) continue;
            try { send(peer, addresses); }
            catch (IOException exception) { try { peer.close(); } catch (IOException ignored) {} }
        }
    }

    private void flushDueSafely() { try { flushDue(); } catch (RuntimeException ignored) {} }

    private void send(Peer peer, List<PeerAddress> addresses) throws IOException {
        long timestamp = Instant.now().getEpochSecond();
        if (peer.remoteWantsAddrV2()) {
            List<AddrV2Entry> entries = new ArrayList<>(addresses.size());
            for (PeerAddress address : addresses) entries.add(new AddrV2Entry(timestamp, address.services(), address.network().bip155Id(), address.rawAddress(), address.port()));
            peer.sendAsync(BitcoinMessages.addrV2(new AddrV2Message(entries)));
        } else {
            List<AddrEntry> entries = new ArrayList<>(addresses.size());
            for (PeerAddress address : addresses) {
                if (address.isLegacyAddrCompatible()) entries.add(AddrEntry.fromIp(timestamp, address.services(), address.address(), address.port()));
            }
            if (!entries.isEmpty()) peer.sendAsync(BitcoinMessages.addr(new AddrMessage(entries)));
        }
    }

    private PeerAddressRelayState state(Peer peer) {
        synchronized (states) { return states.computeIfAbsent(peer, ignored -> new PeerAddressRelayState()); }
    }

    private static long relayScore(Peer peer, PeerAddress address, long rotation) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : address.rawAddress()) { hash ^= b & 0xffL; hash *= 0x100000001b3L; }
        hash ^= address.port(); hash *= 0x100000001b3L;
        hash ^= address.network().bip155Id(); hash *= 0x100000001b3L;
        hash ^= rotation; hash *= 0x100000001b3L;
        hash ^= peer.remoteAddress().hashCode(); hash *= 0x100000001b3L;
        return hash;
    }

    private static long nextDelayNanos(Peer peer, long now) {
        long mixed = relayScore(peer, new PeerAddress(java.net.InetAddress.getLoopbackAddress(), 1, 0), now / 1_000_000_000L);
        double u = ((mixed >>> 11) & ((1L << 53) - 1)) / (double)(1L << 53);
        u = Math.max(u, 1.0e-12);
        return Math.max(1L, (long)(-Math.log(u) * AVG_ADDRESS_BROADCAST_NANOS));
    }

    int queuedCount(Peer peer) { return state(peer).queuedCount(); }
    int knownCount(Peer peer) { return state(peer).knownCount(); }

    @Override public void close() { if (scheduler != null) scheduler.shutdownNow(); synchronized (states) { states.clear(); } }
}
