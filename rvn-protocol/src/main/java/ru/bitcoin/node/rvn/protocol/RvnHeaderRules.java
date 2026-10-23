package ru.bitcoin.node.rvn.protocol;

import java.io.IOException;
import java.util.List;

/**
 * Non-consensus structural checks only. PoW, target, chainwork and block IDs are NOT verified.
 */
public final class RvnHeaderRules {
    private RvnHeaderRules() {
    }

    public static void checkBatch(List<RvnBlockHeader> headers) throws IOException {
        if (headers == null || headers.size() > RvnHeaders.MAX_HEADERS) throw new IOException("Invalid batch size");
        for (int i = 0; i < headers.size(); i++) {
            RvnBlockHeader h = headers.get(i);
            if (h.compactBits() == 0) throw new IOException("Zero compact target at index " + i);
            if (h.isKawpow() && i > 0 && headers.get(i - 1).isKawpow()) {
                long previous = headers.get(i - 1).declaredHeight();
                if (previous == 0xffffffffL || h.declaredHeight() != previous + 1)
                    throw new IOException("Non-consecutive declared KawPoW heights at index " + i);
            }
        }
    }
}
