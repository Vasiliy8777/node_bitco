package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.chain.utxo.CoinMedianTimePastResolver;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockIndexMedianTimePastResolverTest {
    private static final Hash256 ZERO = new Hash256(new byte[32]);

    @Test
    void usesCandidateBranchDespiteAnotherBranchAtSameHeight() {
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        BlockIndex genesis = block(null, 100);
        indexes.put(genesis.hash(), genesis);
        BlockIndex main = genesis;
        BlockIndex side = genesis;
        for (int i = 1; i <= 12; i++) {
            main = block(main, 1000 + i);
            side = block(side, 2000 + i);
            indexes.put(main.hash(), main);
            indexes.put(side.hash(), side);
        }
        // Candidate itself need not be persisted yet.
        CoinMedianTimePastResolver resolver = new BlockIndexMedianTimePastResolver(block(side, 9000), indexes::get);
        assertEquals(2007, resolver.resolvePreviousMedianTimePast(13));
        assertEquals(2006, resolver.resolvePreviousMedianTimePast(12));
        assertEquals(100, resolver.resolvePreviousMedianTimePast(0));
        assertEquals(100, resolver.resolvePreviousMedianTimePast(1));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolvePreviousMedianTimePast(-1));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolvePreviousMedianTimePast(14));
    }

    @Test
    void rejectsMissingHistoryInsteadOfReturningPartialMedian() {
        BlockIndex genesis = block(null, 100);
        BlockIndex parent = block(genesis, 200);
        BlockIndex candidate = block(parent, 300);
        var resolver = new BlockIndexMedianTimePastResolver(candidate,
                hash -> hash.equals(parent.hash()) ? parent : null);
        assertThrows(IllegalStateException.class, () -> resolver.resolvePreviousMedianTimePast(2));
        assertThrows(IllegalStateException.class, () -> resolver.resolvePreviousMedianTimePast(1));
    }

    @Test
    void rejectsHeightGapInsideMedianWindow() {
        BlockIndex genesis = block(null, 100);
        BlockIndex normal = block(genesis, 200);
        BlockIndex malformed = new BlockIndex(normal.hash(), normal.header(), 5,
                genesis.hash(), normal.chainWork());
        BlockIndex candidate = block(malformed, 300);
        Map<Hash256, BlockIndex> indexes = Map.of(genesis.hash(), genesis, malformed.hash(), malformed);
        var resolver = new BlockIndexMedianTimePastResolver(candidate, indexes::get);
        assertThrows(IllegalStateException.class, () -> resolver.resolvePreviousMedianTimePast(6));
    }

    @Test
    void rejectsUnrelatedLookupResultDuringTraversalAndMedianCalculation() {
        BlockIndex genesis = block(null, 100);
        BlockIndex unrelatedGenesis = block(null, 101);
        BlockIndex parent = block(genesis, 200);
        BlockIndex candidate = block(parent, 300);
        BlockIndexLookup lookup = hash -> hash.equals(parent.hash()) ? parent : unrelatedGenesis;
        var resolver = new BlockIndexMedianTimePastResolver(candidate, lookup);
        assertThrows(IllegalStateException.class, () -> resolver.resolvePreviousMedianTimePast(1));
        assertThrows(IllegalStateException.class, () -> resolver.resolvePreviousMedianTimePast(2));
    }

    @Test
    void shortHistoryUsesUpperMedianAndExcludesCandidateTimestamp() {
        BlockIndex genesis = block(null, 100);
        BlockIndex parent = block(genesis, 200);
        var resolver = new BlockIndexMedianTimePastResolver(block(parent, 9000),
                Map.of(genesis.hash(), genesis, parent.hash(), parent)::get);
        assertEquals(200, resolver.resolvePreviousMedianTimePast(2));
    }

    @Test
    void requiresCandidateAndLookup() {
        assertThrows(IllegalArgumentException.class, () -> new BlockIndexMedianTimePastResolver(null, hash -> null));
        assertThrows(IllegalArgumentException.class, () -> new BlockIndexMedianTimePastResolver(block(null, 100), null));
    }

    private static BlockIndex block(BlockIndex parent, long time) {
        Hash256 previous = parent == null ? ZERO : parent.hash();
        BlockHeader header = new BlockHeader(4, previous, ZERO, new UInt32(time),
                new UInt32(0x207fffffL), new UInt32(0));
        return new BlockIndex(header.hash(), header, parent == null ? 0 : parent.height() + 1,
                previous, BigInteger.ONE);
    }
}
