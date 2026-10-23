package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class RvnMainnetDifficultyTest {
    @Test
    void expectedBitsAdapterStartsAtMainnetGenesisAndRejectsUnknownParents() {
        var genesis = RvnMainnetExpectedBits.GENESIS_ID;
        var index = new RvnValidatedHeaderIndex(10, genesis, 0, BigInteger.ZERO,
                RvnMainnetDifficulty.POW_LIMIT);
        byte[] id = new byte[32]; id[0] = 1;
        var candidate = new RvnHeaderCandidate(new RvnHash256(id), genesis, 0x1e00ffffL,
                1514999554L, -1, false);
        var difficulty = new RvnMainnetExpectedBits();
        assertEquals(0x1e00ffffL, difficulty.expected(candidate, index));
        assertThrows(SecurityException.class, () -> difficulty.expected(new RvnHeaderCandidate(
                candidate.id(), candidate.id(), candidate.bits(), candidate.timestamp(), -1, false), index));
    }
    @Test
    void compactEncodingKnownValuesAndSignBoundary() {
        assertEquals(0x1d00ffffL, CompactTarget.encode(CompactTarget.decode(0x1d00ffffL)));
        assertEquals(0x02008000L, CompactTarget.encode(BigInteger.valueOf(128)));
        assertEquals(0x1e0fffffL, CompactTarget.encode(RvnMainnetDifficulty.POW_LIMIT));
        assertEquals(0x1c00ffffL, CompactTarget.encode(RvnMainnetDifficulty.KAWPOW_START_LIMIT));
        assertEquals(0, CompactTarget.encode(BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CompactTarget.encode(BigInteger.ONE.shiftLeft(256)));
    }

    private RvnMainnetDifficulty.History history(long parentHeight, long parentTime, long spacing, long bits) {
        return distance -> new RvnMainnetDifficulty.Ancestor(
                parentHeight - distance, parentTime - spacing * distance, bits);
    }

    @Test
    void historicalIntervalAndDgwBoundary() {
        long time = 1550000000L;
        assertEquals(0x1d00ffffL, RvnMainnetDifficulty.expected(time + 60,
                history(2014, time, 60, 0x1d00ffffL)));
        // The interval spans 2015 gaps; Core divides by 2016 target spacings.
        assertEquals(0x1d00ffdeL, RvnMainnetDifficulty.expected(time + 60,
                history(2015, time, 60, 0x1d00ffffL)));
        assertEquals(0x1d00ffffL, RvnMainnetDifficulty.expected(time + 60,
                history(338776, time, 60, 0x1d00ffffL)));
        assertEquals(0x1d00fe92L, RvnMainnetDifficulty.expected(time + 60,
                history(338777, time, 60, 0x1d00ffffL)));
    }

    @Test
    void kawpowTransitionLastHistoricalBlockAndAllKawpowWindow() {
        long activation = RvnMainnetDifficulty.KAWPOW_TIME;
        var before = history(1200000, activation - 1, 60, 0x1d00ffffL);
        assertEquals(0x1d00fe92L, RvnMainnetDifficulty.expected(activation - 1, before));
        assertEquals(0x1c00ffffL, RvnMainnetDifficulty.expected(activation, before));
        var allKawpow = history(1200180, activation + 179 * 60, 60, 0x1c00ffffL);
        assertEquals(0x1c00fe92L, RvnMainnetDifficulty.expected(activation + 180 * 60, allKawpow));
    }

    @Test
    void dgwClampsAndRejectsMissingHistory() {
        long time = 1600000000L;
        assertEquals(0x1c555500L, RvnMainnetDifficulty.expected(time + 1,
                history(1300000, time, 1, 0x1d00ffffL)));
        assertEquals(0x1d02fffdL, RvnMainnetDifficulty.expected(time + 600,
                history(1300000, time, 600, 0x1d00ffffL)));
        assertThrows(SecurityException.class, () -> RvnMainnetDifficulty.expected(time,
                d -> new RvnMainnetDifficulty.Ancestor(1300000, time, 0x1d00ffffL)));
    }
}
