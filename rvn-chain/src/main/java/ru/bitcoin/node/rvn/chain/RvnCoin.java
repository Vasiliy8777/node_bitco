package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.TxOut;
import java.util.Objects;

/** Immutable native RVN coin metadata; asset state is maintained separately. */
public record RvnCoin(TxOut output, long height, boolean coinbase) {
    public RvnCoin {
        Objects.requireNonNull(output);
        if (height < 0 || height > 0xffff_ffffL) throw new IllegalArgumentException("RVN coin height");
        if (output.value() < 0 || output.value() > RvnBlockBodyChecks.MAX_MONEY)
            throw new IllegalArgumentException("RVN coin amount");
    }
}
