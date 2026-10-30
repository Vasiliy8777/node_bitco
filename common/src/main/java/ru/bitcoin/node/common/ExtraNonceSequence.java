package ru.bitcoin.node.common;

import java.security.SecureRandom;

/** One bijection of the uint64 counter space, shared by its callers. */
public final class ExtraNonceSequence {
    private final long key;
    private long counter;
    private boolean exhausted;

    public ExtraNonceSequence() { this(new SecureRandom().nextLong(), 0); }
    public ExtraNonceSequence(long key, long firstCounter) { this.key = key; this.counter = firstCounter; }

    public synchronized long next() {
        if (exhausted) throw new IllegalStateException("extraNonce uint64 space exhausted");
        long result = NoncePermutation.mix64(counter + key);
        if (++counter == 0) exhausted = true;
        return result;
    }
}
