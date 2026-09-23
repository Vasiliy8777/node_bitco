package ru.bitcoin.node.stratum.job;

import ru.bitcoin.node.protocol.block.Block;
import java.util.Objects;

/** The coinbase script ends with sixteen reserved zero bytes for extranonce1 + extranonce2. */
public record MiningWork(Block block, long minimumTime, long revision, boolean timeRolling) {
    public MiningWork {
        Objects.requireNonNull(block);
        if (minimumTime < 0 || minimumTime > block.header().timestamp().value())
            throw new IllegalArgumentException("Invalid minimum mining time");
    }
}
