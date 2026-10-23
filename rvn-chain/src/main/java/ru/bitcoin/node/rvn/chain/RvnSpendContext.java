package ru.bitcoin.node.rvn.chain;

import java.util.Objects;
import java.util.function.LongUnaryOperator;

/** Caller-supplied RVN branch context. The MTP lookup receives a block height
 * on the candidate's parent branch, not a coin height or the best-header tip. */
public record RvnSpendContext(long height, long lockTimeCutoff, long parentMedianTimePast,
                              boolean sequenceLocks, int scriptFlags, LongUnaryOperator medianTimePastAtHeight) {
    public RvnSpendContext {
        if (height < 1 || height > 0xffff_ffffL || lockTimeCutoff < 0
                || parentMedianTimePast < 0 || parentMedianTimePast > 0xffff_ffffL)
            throw new IllegalArgumentException("RVN spending context");
        RvnNativeScriptChecks.requireFlags(scriptFlags);
        Objects.requireNonNull(medianTimePastAtHeight);
    }
}
