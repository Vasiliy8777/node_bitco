package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bitcoin Core style assumevalid gate. It may skip transaction input script
 * execution only; every other block/transaction/UTXO consensus rule remains active.
 */
public final class AssumeValidPolicy {
    public static final long ASSUME_VALID_DEPTH_SECONDS = 14L * 24L * 60L * 60L;

    private static final Hash256 ZERO_HASH = new Hash256(new byte[Hash256.LENGTH]);
    /**
     * Number of consecutive ancestry results retained per descendant tip.
     *
     * IBD advances monotonically while the two descendants used by assume-valid
     * (the configured assume-valid block and the best header) are effectively
     * stable. Bound each refill so that cold block-index reads cannot delay
     * the first block connection for minutes while holding the chain lock.
     */
    private static final int ANCESTRY_WINDOW = 128;

    private final BlockIndexLookup lookup;
    private final Supplier<BlockIndex> bestHeaderSupplier;
    private final NetworkParameters parameters;
    private final Hash256 assumedValidBlock;

    /*
     * During IBD the same two descendants (assume-valid and best-header) are
     * queried for consecutive candidate heights. A skip traversal per block is
     * correct but needlessly re-reads hundreds of block-index records. Cache a
     * small, branch-specific window of exact height -> hash proofs instead.
     */
    private final Map<Hash256, AncestryWindow> ancestryWindows = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash256, AncestryWindow> eldest) {
            return size() > 4;
        }
    };
    private Hash256 provenBestHeader;

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
        if (bestHeader == null || candidate.height() > bestHeader.height()) return true;

        if (bestHeader.chainWork().compareTo(parameters.minimumChainWork()) < 0) return true;
        // If candidate is in the assumed chain and best contains the assumed
        // block, transitivity proves the same exact best-chain membership.
        // Only a positive proof for this immutable best hash can be reused.
        if (!bestIncludesAssumed(assumed, bestHeader) && !isAncestor(candidate, bestHeader)) return true;

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

    private synchronized Hash256 cachedAncestorHash(
            BlockIndexAncestorLookup ancestorLookup,
            BlockIndex descendant,
            long targetHeight
    ) {
        AncestryWindow window = ancestryWindows.get(descendant.hash());
        if (window != null && targetHeight >= window.lowHeight && targetHeight <= window.highHeight) {
            return window.hashesByHeight.get(targetHeight);
        }

        long highHeight = Math.min(descendant.height(), targetHeight + ANCESTRY_WINDOW - 1L);
        BlockIndex cursor = ancestorLookup.ancestor(descendant, highHeight, AssumeValidPolicy::checkInterrupted);
        if (cursor == null) return null;

        Map<Long, Hash256> hashes = new HashMap<>(ANCESTRY_WINDOW * 2);
        hashes.put(cursor.height(), cursor.hash());
        while (cursor.height() > targetHeight) {
            checkInterrupted();
            cursor = lookup.find(cursor.previousBlockHash());
            if (cursor == null) {
                throw new IllegalStateException("Missing ancestry while filling assume-valid window");
            }
            hashes.put(cursor.height(), cursor.hash());
        }

        ancestryWindows.put(descendant.hash(), new AncestryWindow(targetHeight, highHeight, hashes));
        return hashes.get(targetHeight);
    }

    private record AncestryWindow(long lowHeight, long highHeight, Map<Long, Hash256> hashesByHeight) {
    }

    private synchronized boolean bestIncludesAssumed(BlockIndex assumed, BlockIndex best) {
        if (best.hash().equals(provenBestHeader)) return true;
        if (best.height() < assumed.height() || !(lookup instanceof BlockIndexAncestorLookup ancestors)) {
            return false;
        }
        try {
            BlockIndex ancestor = ancestors.ancestor(best, assumed.height(), AssumeValidPolicy::checkInterrupted);
            if (ancestor != null && ancestor.hash().equals(assumed.hash())) {
                provenBestHeader = best.hash();
                return true;
            }
        } catch (IllegalStateException missingAncestry) {
            // Do not memoize failure: additional headers may fill this ancestry.
        }
        return false;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Interrupted assume-valid ancestry lookup");
        }
    }

    private boolean isAncestor(BlockIndex ancestor, BlockIndex descendant) {
        if (ancestor.height() > descendant.height()) {
            return false;
        }

        /*
         * Assume-valid is evaluated for every connected block during IBD.  Walking
         * backwards one parent at a time from the assumed-valid/best-header tip
         * makes the first historical block perform millions of RocksDB lookups.
         * StoredBlockIndexLookup already exposes Bitcoin Core-style skip ancestry,
         * so use it whenever available.
         */
        if (lookup instanceof BlockIndexAncestorLookup ancestorLookup) {
            try {
                Hash256 resolvedHash = cachedAncestorHash(ancestorLookup, descendant, ancestor.height());
                return resolvedHash != null && resolvedHash.equals(ancestor.hash());
            } catch (IllegalStateException missingAncestry) {
                // Preserve the old fail-safe behaviour: an incomplete ancestry means
                // assume-valid must not skip script verification.
                return false;
            }
        }

        BlockIndex cursor = descendant;
        while (cursor.height() > ancestor.height()) {
            checkInterrupted();
            cursor = lookup.find(cursor.previousBlockHash());
            if (cursor == null) return false;
        }
        return cursor.hash().equals(ancestor.hash());
    }
}
