package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.network.NetworkParameters;

/** Validation context bound to the branch of one candidate block. */
public final class AncestorMedianTimePastResolver {
    private final BlockIndex candidateBlock;
    private final BlockIndexLookup blockIndexLookup;
    private final BlockIndexMedianTimePastResolver coinTimeResolver;

    public AncestorMedianTimePastResolver(BlockIndex candidateBlock, BlockIndexLookup blockIndexLookup) {
        coinTimeResolver = new BlockIndexMedianTimePastResolver(candidateBlock, blockIndexLookup);
        this.candidateBlock = candidateBlock;
        this.blockIndexLookup = blockIndexLookup;
    }

    public long resolveForCoinHeight(long coinHeight) {
        return coinTimeResolver.resolvePreviousMedianTimePast(coinHeight);
    }

    public boolean taprootActive(NetworkParameters parameters) {
        return TaprootDeployment.activeFor(candidateBlock, blockIndexLookup, parameters);
    }
}
