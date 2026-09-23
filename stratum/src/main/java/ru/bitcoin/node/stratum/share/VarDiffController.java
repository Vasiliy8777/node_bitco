package ru.bitcoin.node.stratum.share;

import java.math.*;

/** Per-session sample window, driven by monotonic time. Only accepted current-epoch shares count. */
public final class VarDiffController {
    private final VarDiffConfig config;
    private BigDecimal difficulty;
    private boolean started;
    private long windowStart;
    private long accepted;

    public VarDiffController(VarDiffConfig config, BigDecimal initial) {
        this.config = java.util.Objects.requireNonNull(config);
        new ShareValidator(initial);
        if (config.enabled() && (initial.compareTo(config.minimum()) < 0 || initial.compareTo(config.maximum()) > 0))
            throw new IllegalArgumentException("Initial difficulty must lie within vardiff bounds");
        difficulty = initial;
    }

    public BigDecimal difficulty() { return difficulty; }

    public void accepted() {
        if (started && config.enabled()) accepted++;
    }

    public boolean update(long now) {
        if (!config.enabled()) return false;
        if (!started) { started = true; windowStart = now; return false; }
        long elapsed = now - windowStart;
        if (elapsed < config.retargetInterval().toNanos()) return false;
        BigDecimal factor = BigDecimal.valueOf(accepted).multiply(BigDecimal.valueOf(config.targetInterval().toNanos()))
                .divide(BigDecimal.valueOf(elapsed), MathContext.DECIMAL64);
        windowStart = now;
        accepted = 0;
        // A 25% dead band avoids chasing ordinary share-arrival noise.
        if (factor.compareTo(new BigDecimal("0.75")) >= 0 && factor.compareTo(new BigDecimal("1.25")) <= 0) return false;
        factor = factor.max(new BigDecimal("0.25")).min(new BigDecimal("4"));
        BigDecimal next = difficulty.multiply(factor).setScale(12, RoundingMode.HALF_UP)
                .max(config.minimum()).min(config.maximum()).stripTrailingZeros();
        if (next.compareTo(difficulty) == 0) return false;
        difficulty = next;
        return true;
    }
}
