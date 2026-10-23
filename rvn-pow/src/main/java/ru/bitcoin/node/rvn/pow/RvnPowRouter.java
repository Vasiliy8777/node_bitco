package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Fail-closed height-based dispatch. KawPoW requires independently computed mix.
 */
public final class RvnPowRouter {
    private final RvnPowSchedule schedule;
    private final RvnPowEngine engine;

    public RvnPowRouter(RvnPowSchedule schedule, RvnPowEngine engine) {
        this.schedule = Objects.requireNonNull(schedule);
        this.engine = Objects.requireNonNull(engine);
    }

    public RvnPowResult calculate(long height, byte[] raw) throws RvnPowException {
        var algorithm = schedule.atHeight(height);
        RvnPowHeaderRules.requireHeader(algorithm, raw);
        if (algorithm == RvnPowAlgorithm.KAWPOW) {
            if (RvnPowHeaderRules.kawpowDeclaredHeight(raw) != height)
                throw new RvnPowException("KawPoW declared height mismatch");
            if (!(engine instanceof RvnKawpowMixEngine mix))
                throw new RvnPowException("KawPoW requires independently verified mix");
            return RvnKawpowProof.requireValidMix(mix, raw);
        }
        return Objects.requireNonNull(engine.calculate(algorithm, raw.clone()),
                "PoW engine returned null");
    }
}
