package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Network-specific activation heights MUST be independently verified against Ravencoin Core.
 */
public record RvnPowSchedule(long x16rv2Height, long kawpowHeight) {
    public RvnPowSchedule {
        if (x16rv2Height <= 0 || kawpowHeight <= x16rv2Height)
            throw new IllegalArgumentException("Invalid PoW activation heights");
    }

    public RvnPowAlgorithm atHeight(long height) {
        if (height < 0) throw new IllegalArgumentException("height");
        return height >= kawpowHeight ? RvnPowAlgorithm.KAWPOW :
                height >= x16rv2Height ? RvnPowAlgorithm.X16RV2 : RvnPowAlgorithm.X16R;
    }
}
