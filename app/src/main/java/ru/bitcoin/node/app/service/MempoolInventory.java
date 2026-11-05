package ru.bitcoin.node.app.service;

import ru.bitcoin.node.mempool.MempoolEntry;
import java.math.BigInteger;

/** Core BIP133 response comparison: base fee versus CFeeRate::GetFee(vsize). */
final class MempoolInventory {
    static boolean passesFeeFilter(MempoolEntry entry, long rate) {
        if (rate < 0) throw new IllegalArgumentException("Negative fee filter");
        if (rate == 0) return entry.fee() >= 0;
        var required = BigInteger.valueOf(rate).multiply(BigInteger.valueOf(entry.virtualSize()))
                .add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000));
        return BigInteger.valueOf(entry.fee()).compareTo(required) >= 0;
    }
}
