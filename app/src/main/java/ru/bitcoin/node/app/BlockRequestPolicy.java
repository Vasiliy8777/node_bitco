package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.consensus.pow.ChainWork;
import java.math.BigInteger;

/** Core BlockRequestAllowed and compact response limits. */
final class BlockRequestPolicy {
    static final long STALE_RELAY_AGE_SECONDS = 30L * 24 * 60 * 60;

    static boolean limitedHistoryExceeded(long services, long tipHeight, long requestedHeight) {
        return (services & ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED) != 0
                && (services & ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK) == 0
                && tipHeight - requestedHeight > 290;
    }

    static boolean compactAllowed(long candidateHeight, BlockIndex tip, long nowSeconds, long targetSpacingSeconds) {
        return candidateHeight >= tip.height() - 5
                && tip.header().timestamp().value() > nowSeconds - 20 * targetSpacingSeconds;
    }

    static boolean allowsSideChain(BlockIndex candidate, BlockIndex bestHeader,
                                   boolean scriptsValid, long targetSpacingSeconds) {
        if (!scriptsValid || bestHeader == null) return false;
        if (bestHeader.header().timestamp().value() - candidate.header().timestamp().value()
                >= STALE_RELAY_AGE_SECONDS) return false;
        BigInteger equivalentSeconds = bestHeader.chainWork().subtract(candidate.chainWork())
                .multiply(BigInteger.valueOf(targetSpacingSeconds))
                .divide(ChainWork.blockWork(bestHeader.header().bits().value()));
        return equivalentSeconds.compareTo(BigInteger.valueOf(STALE_RELAY_AGE_SECONDS)) < 0;
    }
}
