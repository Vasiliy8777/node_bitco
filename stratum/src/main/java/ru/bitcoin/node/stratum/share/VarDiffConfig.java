package ru.bitcoin.node.stratum.share;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record VarDiffConfig(boolean enabled, BigDecimal minimum, BigDecimal maximum,
                            Duration targetInterval, Duration retargetInterval) {
    public VarDiffConfig {
        Objects.requireNonNull(minimum);
        Objects.requireNonNull(maximum);
        Objects.requireNonNull(targetInterval);
        Objects.requireNonNull(retargetInterval);
        new ShareValidator(minimum);
        new ShareValidator(maximum);
        if (minimum.compareTo(new BigDecimal("1e-12")) < 0 || maximum.compareTo(new BigDecimal("1e18")) > 0
                || minimum.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Vardiff requires 1e-12 <= minimum <= maximum <= 1e18");
        if (targetInterval.compareTo(Duration.ofSeconds(1)) < 0
                || retargetInterval.compareTo(targetInterval) < 0 || retargetInterval.compareTo(Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("Vardiff requires 1s <= target interval <= retarget interval <= 120s");
    }

    public static VarDiffConfig disabled() {
        return new VarDiffConfig(false, BigDecimal.ONE, new BigDecimal("1e12"), Duration.ofSeconds(15), Duration.ofSeconds(60));
    }
}
