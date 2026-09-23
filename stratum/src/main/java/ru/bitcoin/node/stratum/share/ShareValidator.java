package ru.bitcoin.node.stratum.share;

import ru.bitcoin.node.consensus.pow.*;
import ru.bitcoin.node.stratum.job.MiningJob;
import ru.bitcoin.node.stratum.protocol.StratumException;
import java.math.*;

public final class ShareValidator {
    private static final BigInteger DIFFICULTY_ONE = CompactTarget.decode(0x1d00ffffL);
    private static final BigInteger MAX_TARGET = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private final BigInteger shareTarget;

    public ShareValidator(BigDecimal difficulty) {
        if (difficulty == null || difficulty.signum() <= 0 || difficulty.precision() > 32 || Math.abs((long)difficulty.scale()) > 32)
            throw new IllegalArgumentException("Invalid share difficulty");
        shareTarget = new BigDecimal(DIFFICULTY_ONE).divide(difficulty, 0, RoundingMode.DOWN).toBigIntegerExact().min(MAX_TARGET);
        if (shareTarget.signum() <= 0) throw new IllegalArgumentException("Difficulty exceeds target precision");
    }

    public boolean validate(MiningJob job, MiningJob.Candidate candidate, long now) {
        var header = candidate.header();
        long time = header.timestamp().value();
        if (time < job.work().minimumTime() || time > now + 7200
                || (!job.work().timeRolling() && time != job.work().block().header().timestamp().value()))
            throw new StratumException(20, "Invalid ntime");
        var hash = ProofOfWork.hashToInteger(header.hash());
        var networkTarget = CompactTarget.decode(header.bits().value());
        boolean block = hash.compareTo(networkTarget) <= 0;
        // A real block is valuable even if the configured share target is harder (e.g. regtest).
        if (!block && hash.compareTo(shareTarget) > 0) throw new StratumException(23, "Low difficulty share");
        return block;
    }
}
