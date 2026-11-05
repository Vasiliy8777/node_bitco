package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class BlockRequestPolicyTest {
    private static final long LIMIT = 30L * 24 * 60 * 60;

    @Test void limitedHistoryWindowUsesAdvertisedFlagsAndStrict290BlockBoundary() {
        long limited = ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED;
        long full = ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK;
        assertFalse(BlockRequestPolicy.limitedHistoryExceeded(limited, 300, 10));
        assertTrue(BlockRequestPolicy.limitedHistoryExceeded(limited, 300, 9));
        assertFalse(BlockRequestPolicy.limitedHistoryExceeded(limited | full, 300, 9));
        assertFalse(BlockRequestPolicy.limitedHistoryExceeded(full, 300, 9));
        assertFalse(BlockRequestPolicy.limitedHistoryExceeded(0, 300, 9));
        assertFalse(BlockRequestPolicy.limitedHistoryExceeded(limited, 300, 301));
    }

    @Test void compactResponsesRequireFreshTipAndInclusiveFiveBlockDepth() {
        var header = index(1_700_000_000L, 10).header();
        var tip = new BlockIndex(header.hash(), header, 100, header.previousBlockHash(), BigInteger.TEN);
        assertTrue(BlockRequestPolicy.compactAllowed(95, tip, 1_700_011_999L, 600));
        assertFalse(BlockRequestPolicy.compactAllowed(95, tip, 1_700_012_000L, 600));
        assertFalse(BlockRequestPolicy.compactAllowed(94, tip, 1_700_000_000L, 600));
        assertTrue(BlockRequestPolicy.compactAllowed(101, tip, 1_700_000_000L, 600));
    }

    @Test void requiresScriptValidationAndStrictTimestampAndEquivalentWorkAgeLimits() {
        var candidate = index(1_700_000_000L, 10);
        assertTrue(allowed(candidate, index(1_700_000_000L + LIMIT - 1, 11)));
        assertFalse(allowed(candidate, index(1_700_000_000L + LIMIT, 11)));
        // Regtest nBits gives proof=2; 8640 work units represent exactly 30 days.
        assertTrue(allowed(candidate, index(1_700_000_001L, 10 + 8639)));
        assertFalse(allowed(candidate, index(1_700_000_001L, 10 + 8640)));
        assertFalse(BlockRequestPolicy.allowsSideChain(candidate, null, true, 600));
        assertFalse(BlockRequestPolicy.allowsSideChain(candidate, candidate, false, 600));
        assertTrue(allowed(candidate, index(1_699_999_999L, 9)));
    }

    private static boolean allowed(BlockIndex candidate, BlockIndex best) {
        return BlockRequestPolicy.allowsSideChain(candidate, best, true, 600);
    }

    private static BlockIndex index(long timestamp, long work) {
        var zero = new Hash256(new byte[32]);
        var header = new BlockHeader(4, zero, zero, new UInt32(timestamp), new UInt32(0x207fffffL), new UInt32(0));
        return new BlockIndex(header.hash(), header, 1, zero, BigInteger.valueOf(work));
    }
}
