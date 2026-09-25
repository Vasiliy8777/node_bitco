package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InitialBlockDownloadStateTest {
    private static final Hash256 ZERO = new Hash256(new byte[32]);

    @Test
    void remainsInIbdBelowMinimumChainWorkEvenWithRecentTip() {
        var now = new AtomicLong(2_000_000L);
        var state = new InitialBlockDownloadState(BigInteger.valueOf(100), Duration.ofHours(24), now::get);
        assertTrue(state.update(index(10, 99, now.get())));
        assertTrue(state.isInitialBlockDownload());
    }

    @Test
    void remainsInIbdWithOldTipEvenAfterMinimumWork() {
        var now = new AtomicLong(2_000_000L);
        var state = new InitialBlockDownloadState(BigInteger.valueOf(100), Duration.ofHours(24), now::get);
        assertTrue(state.update(index(10, 100, now.get() - Duration.ofHours(24).getSeconds() - 1)));
    }

    @Test
    void exitsWhenWorkAndTipAgeRequirementsAreSatisfied() {
        var now = new AtomicLong(2_000_000L);
        var state = new InitialBlockDownloadState(BigInteger.valueOf(100), Duration.ofHours(24), now::get);
        assertFalse(state.update(index(10, 100, now.get() - Duration.ofHours(24).getSeconds())));
        assertFalse(state.isInitialBlockDownload());
    }

    @Test
    void exitIsLatchedAcrossOldTipAndClockChanges() {
        var now = new AtomicLong(2_000_000L);
        var state = new InitialBlockDownloadState(BigInteger.valueOf(100), Duration.ofHours(24), now::get);
        assertFalse(state.update(index(10, 100, now.get())));
        now.addAndGet(Duration.ofDays(30).getSeconds());
        assertFalse(state.update(index(9, 1, 1L)));
        assertFalse(state.isInitialBlockDownload());
    }

    private static BlockIndex index(long height, long work, long timestamp) {
        var header = new BlockHeader(4, ZERO, ZERO, new UInt32(timestamp), new UInt32(0x207fffffL), new UInt32(height));
        return new BlockIndex(header.hash(), header, height, ZERO, BigInteger.valueOf(work));
    }
}
