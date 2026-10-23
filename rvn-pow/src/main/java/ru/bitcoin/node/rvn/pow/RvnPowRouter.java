package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Timestamp-based dispatch; height independently checks the KawPoW header claim.
 */
public final class RvnPowRouter {
    private final RvnPowSchedule schedule;
    private final RvnPowEngine engine;

    public RvnPowRouter(RvnPowSchedule schedule, RvnPowEngine engine) {
        this.schedule = Objects.requireNonNull(schedule);
        this.engine = Objects.requireNonNull(engine);
    }

    public RvnPowResult calculate(long height, byte[] raw) throws RvnPowException {
        if (height < 0) throw new IllegalArgumentException("height");
        var algorithm = schedule.atTimestamp(RvnPowHeaderRules.unsigned32(raw, 68));
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
