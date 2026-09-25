package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bitcoin Core style assumevalid gate. It may skip transaction input script
 * execution only; every other block/transaction/UTXO consensus rule remains active.
 */
public final class AssumeValidPolicy {
    public static final long ASSUME_VALID_DEPTH_SECONDS = 14L * 24L * 60L * 60L;

    private static final Hash256 ZERO_HASH = new Hash256(new byte[Hash256.LENGTH]);

    private final BlockIndexLookup lookup;
    private final Supplier<BlockIndex> bestHeaderSupplier;
    private final NetworkParameters parameters;
    private final Hash256 assumedValidBlock;

    public AssumeValidPolicy(BlockIndexLookup lookup,
                             Supplier<BlockIndex> bestHeaderSupplier,
                             NetworkParameters parameters,
                             Hash256 assumedValidBlock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.bestHeaderSupplier = Objects.requireNonNull(bestHeaderSupplier, "bestHeaderSupplier");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.assumedValidBlock = Objects.requireNonNull(assumedValidBlock, "assumedValidBlock");
    }

    public static AssumeValidPolicy verifyAll(BlockIndexLookup lookup, NetworkParameters parameters) {
        return new AssumeValidPolicy(lookup, () -> null, parameters, ZERO_HASH);
    }

    /** Returns true when input scripts for this block must be executed. */
    public boolean shouldVerifyScripts(BlockIndex candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (assumedValidBlock.equals(ZERO_HASH)) return true;

        BlockIndex assumed = lookup.find(assumedValidBlock);
        if (assumed == null || candidate.height() > assumed.height()
                || !isAncestor(candidate, assumed)) return true;

        BlockIndex bestHeader = bestHeaderSupplier.get();
        if (bestHeader == null || candidate.height() > bestHeader.height()
                || !isAncestor(candidate, bestHeader)) return true;

        if (bestHeader.chainWork().compareTo(parameters.minimumChainWork()) < 0) return true;

        return proofEquivalentTime(bestHeader, candidate, bestHeader) <= ASSUME_VALID_DEPTH_SECONDS;
    }

    long proofEquivalentTime(BlockIndex to, BlockIndex from, BlockIndex tip) {
        BigInteger workDiff = to.chainWork().subtract(from.chainWork()).abs();
        BigInteger tipProof = ChainWork.blockWork(tip.header().bits().value());
        BigInteger seconds = workDiff
                .multiply(BigInteger.valueOf(parameters.targetSpacingSeconds()))
                .divide(tipProof);
        return seconds.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE : seconds.longValue();
    }

    private boolean isAncestor(BlockIndex ancestor, BlockIndex descendant) {
        BlockIndex cursor = descendant;
        while (cursor.height() > ancestor.height()) {
            cursor = lookup.find(cursor.previousBlockHash());
            if (cursor == null) return false;
        }
        return cursor.hash().equals(ancestor.hash());
    }
}
