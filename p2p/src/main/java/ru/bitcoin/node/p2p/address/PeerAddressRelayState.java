package ru.bitcoin.node.p2p.address;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Per-peer state for Bitcoin address gossip. */
final class PeerAddressRelayState {
    static final int MAX_KNOWN = 5_000;
    static final int MAX_QUEUED = 1_000;

    private final LinkedHashSet<PeerAddressKey> known = new LinkedHashSet<>();
    private final ArrayDeque<PeerAddress> queued = new ArrayDeque<>();
    private boolean enabled;
    private long nextSendNanos;

    synchronized boolean enable() {
        boolean changed = !enabled;
        enabled = true;
        return changed;
    }

    synchronized boolean enabled() { return enabled; }

    synchronized void markKnown(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        PeerAddressKey key = PeerAddressKey.from(address);
        if (known.remove(key)) known.add(key);
        else {
            known.add(key);
            while (known.size() > MAX_KNOWN) {
                Iterator<PeerAddressKey> iterator = known.iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    synchronized boolean knows(PeerAddress address) {
        return known.contains(PeerAddressKey.from(address));
    }

    synchronized void queue(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        if (!enabled || knows(address)) return;
        PeerAddressKey key = PeerAddressKey.from(address);
        for (PeerAddress existing : queued) {
            if (PeerAddressKey.from(existing).equals(key)) return;
        }
        if (queued.size() >= MAX_QUEUED) queued.removeFirst();
        queued.addLast(address);
    }

    synchronized List<PeerAddress> drainCompatible(boolean wantsAddrV2) {
        List<PeerAddress> result = new ArrayList<>();
        while (!queued.isEmpty()) {
            PeerAddress address = queued.removeFirst();
            if (knows(address)) continue;
            if (!wantsAddrV2 && !address.isLegacyAddrCompatible()) continue;
            markKnown(address);
            result.add(address);
        }
        return result;
    }

    synchronized int queuedCount() { return queued.size(); }
    synchronized int knownCount() { return known.size(); }
    synchronized long nextSendNanos() { return nextSendNanos; }
    synchronized void nextSendNanos(long value) { nextSendNanos = value; }
}
