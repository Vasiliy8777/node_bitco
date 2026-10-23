package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Timestamp-based algorithm selection, as in Core CBlockHeader::GetHash.
 */
public record RvnPowSchedule(long x16rv2Time, long kawpowTime) {
    public RvnPowSchedule {
        if (x16rv2Time <= 0 || kawpowTime <= x16rv2Time || kawpowTime > 0xffff_ffffL)
            throw new IllegalArgumentException("Invalid PoW activation timestamps");
    }

    public static RvnPowSchedule mainnet() {
        return new RvnPowSchedule(1_569_945_600L, 1_588_788_000L);
    }

    public RvnPowAlgorithm atTimestamp(long timestamp) {
        if (timestamp < 0 || timestamp > 0xffff_ffffL) throw new IllegalArgumentException("timestamp");
        return timestamp >= kawpowTime ? RvnPowAlgorithm.KAWPOW :
                timestamp >= x16rv2Time ? RvnPowAlgorithm.X16RV2 : RvnPowAlgorithm.X16R;
    }
}
