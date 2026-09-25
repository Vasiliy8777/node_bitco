package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-peer transaction relay policy state.
 *
 * Mirrors the two Core concepts needed by the relay path:
 * the BIP133 fee filter received from the peer and a bounded set of
 * transaction identifiers already known by that peer.
 */
final class TxRelayState {

    static final int MAX_KNOWN_TRANSACTION_IDS = 50_000;

    private long feeFilterSatPerKvB;

    private final LinkedHashMap<Hash256, Boolean> known =
            new LinkedHashMap<>(256, 0.75f, true);

    /*
     * Core keeps wtxids pending for trickled transaction inventory relay.
     * LinkedHashMap gives deterministic insertion/dependency order for our
     * current mempool admission path while also suppressing duplicate queueing.
     */
    private final LinkedHashMap<Hash256, PendingAnnouncement> pending =
            new LinkedHashMap<>();

    private long nextInventorySendNanos;

    record PendingAnnouncement(
            Hash256 txId,
            Hash256 wtxId,
            long feeRateSatPerKvB
    ) {
        PendingAnnouncement {
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(wtxId, "wtxId");
            if (feeRateSatPerKvB < 0) {
                throw new IllegalArgumentException("fee rate must not be negative");
            }
        }
    }

    synchronized long feeFilterSatPerKvB() {
        return feeFilterSatPerKvB;
    }

    synchronized void feeFilterSatPerKvB(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("fee filter must not be negative");
        }
        feeFilterSatPerKvB = value;
    }

    synchronized void markKnown(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        known.put(hash, Boolean.TRUE);

        while (known.size() > MAX_KNOWN_TRANSACTION_IDS) {
            var iterator = known.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    synchronized boolean knows(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        return known.containsKey(hash);
    }

    synchronized int knownCount() {
        return known.size();
    }

    synchronized void queue(Hash256 txId, Hash256 wtxId, long feeRateSatPerKvB) {
        PendingAnnouncement announcement =
                new PendingAnnouncement(txId, wtxId, feeRateSatPerKvB);
        pending.putIfAbsent(wtxId, announcement);
    }

    synchronized List<PendingAnnouncement> takeForRelay(
            boolean wtxidRelay,
            int maximum
    ) {
        if (maximum <= 0 || pending.isEmpty()) {
            return List.of();
        }

        List<PendingAnnouncement> selected =
                new ArrayList<>(Math.min(maximum, pending.size()));

        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && selected.size() < maximum) {
            PendingAnnouncement announcement = iterator.next().getValue();
            Hash256 advertised =
                    wtxidRelay
                            ? announcement.wtxId()
                            : announcement.txId();

            /*
             * Re-check known inventory and BIP133 at send time. The peer may
             * have announced the transaction or changed its feefilter while
             * the item was waiting for its trickle slot.
             */
            if (knows(advertised)
                    || announcement.feeRateSatPerKvB() < feeFilterSatPerKvB) {
                iterator.remove();
                continue;
            }

            selected.add(announcement);
            iterator.remove();
        }

        return List.copyOf(selected);
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    synchronized long nextInventorySendNanos() {
        return nextInventorySendNanos;
    }

    synchronized void nextInventorySendNanos(long value) {
        nextInventorySendNanos = value;
    }

}
