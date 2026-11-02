package ru.bitcoin.node.p2p;

/** Shared Core-style recent GETHEADERS admission, across all producers for one peer. */
final class PeerHeaderRequestGate {
    private long generation;
    private long sentAtMicros;
    private boolean outstanding;

    synchronized long acquire(long nowMicros) {
        if (outstanding && nowMicros - sentAtMicros <= 120_000_000) return 0;
        outstanding = true;
        sentAtMicros = nowMicros;
        return ++generation;
    }

    synchronized void responded() { outstanding = false; }
    synchronized void failed(long token) { if (token == generation) outstanding = false; }
}
