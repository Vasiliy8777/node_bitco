package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;

import java.util.LinkedHashMap;
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
}
