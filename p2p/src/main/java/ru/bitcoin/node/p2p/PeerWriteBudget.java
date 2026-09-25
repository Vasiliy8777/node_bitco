package ru.bitcoin.node.p2p;

import java.io.IOException;

/** Admission limits for both waiting and active transport writes, before encoding. */
final class PeerWriteBudget {
    static final PeerWriteBudget SHARED = new PeerWriteBudget(
            64, 1024, 16L * 1024 * 1024, 64L * 1024 * 1024);

    private final int perPeerCount;
    private final int globalCount;
    private final long perPeerBytes;
    private final long globalBytes;
    private int count;
    private long bytes;

    PeerWriteBudget(int perPeerCount, int globalCount, long perPeerBytes, long globalBytes) {
        if (perPeerCount <= 0 || globalCount <= 0 || perPeerBytes <= 0 || globalBytes <= 0) {
            throw new IllegalArgumentException("Write limits must be positive");
        }
        this.perPeerCount = perPeerCount;
        this.globalCount = globalCount;
        this.perPeerBytes = perPeerBytes;
        this.globalBytes = globalBytes;
    }

    Account newAccount() { return new Account(); }

    final class Account {
        private int pendingCount;
        private long pendingBytes;

        Reservation reserve(int payloadLength) throws IOException {
            if (payloadLength < 0) throw new IllegalArgumentException("Negative payload length");
            // Retained message, encoder payload clone, wire packet and fixed overhead.
            // Accounting units, not an exact JVM heap measurement.
            long charge = 256L + 3L * payloadLength;
            synchronized (PeerWriteBudget.this) {
                if (pendingCount >= perPeerCount || count >= globalCount
                        || charge > perPeerBytes - pendingBytes || charge > globalBytes - bytes) {
                    throw new IOException("Peer transport write budget exhausted");
                }
                pendingCount++;
                count++;
                pendingBytes += charge;
                bytes += charge;
                return new Reservation(this, charge);
            }
        }
    }

    final class Reservation implements AutoCloseable {
        private final Account account;
        private final long charge;
        private boolean released;

        private Reservation(Account account, long charge) {
            this.account = account;
            this.charge = charge;
        }

        @Override public void close() {
            synchronized (PeerWriteBudget.this) {
                if (released) return;
                released = true;
                account.pendingCount--;
                count--;
                account.pendingBytes -= charge;
                bytes -= charge;
            }
        }
    }
}
