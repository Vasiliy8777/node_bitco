package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Dispatch by independently established block height; never infer epoch from timestamp.
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
        if (algorithm == RvnPowAlgorithm.KAWPOW &&
                RvnPowHeaderRules.kawpowDeclaredHeight(raw) != height)
            throw new RvnPowException("KawPoW declared height mismatch");
        return engine.calculate(algorithm, raw.clone());
    }
}
