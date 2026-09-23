package ru.bitcoin.node.stratum.job;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.Block;
import java.util.Optional;

/** The application owns chain state, readiness, contextual validation and block publication. */
public interface MiningBackend {
    Optional<MiningWork> work();
    boolean isCurrent(Hash256 parent);
    boolean submit(Block block);
    long currentTimeSeconds();
}
