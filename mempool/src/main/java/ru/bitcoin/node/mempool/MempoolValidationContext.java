package ru.bitcoin.node.mempool;

import java.util.Objects;
import java.util.function.LongUnaryOperator;

/** Snapshot of the active chain for next-block admission.
 * The caller must keep the chain/UTXO snapshot stable during admission.
 * coinPreviousMedianTimePast resolves MTP at max(coinHeight - 1, 0) on that chain.
 */
public record MempoolValidationContext(long nextBlockHeight, long tipMedianTimePast,
                                       LongUnaryOperator coinPreviousMedianTimePast) {
    public MempoolValidationContext {
        if (nextBlockHeight < 1 || tipMedianTimePast < 0) {
            throw new IllegalArgumentException("Invalid next-block height or tip MTP");
        }
        Objects.requireNonNull(coinPreviousMedianTimePast, "coinPreviousMedianTimePast");
    }
}
