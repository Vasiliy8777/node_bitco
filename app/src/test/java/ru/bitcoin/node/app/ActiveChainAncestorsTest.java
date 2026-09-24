package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ActiveChainAncestorsTest {
    @Test
    void repeatedHistoricalQueriesReuseReadsWithBoundedCache() {
        var indexes = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = index(0, 0, hash(-1));
        indexes.put(tip.hash(), tip);
        for (int i = 1; i <= 10_000; i++) {
            tip = index(i, i, tip.hash()); indexes.put(tip.hash(), tip);
        }
        var cache = new ActiveChainAncestors(128);
        var reads = new AtomicInteger();
        ru.bitcoin.node.chain.BlockIndexLookup lookup = hash -> { reads.incrementAndGet(); return indexes.get(hash); };
        assertEquals(hash(50), cache.at(tip, 50, lookup).hash());
        assertEquals(9950, reads.get());
        for (int i = 50; i < 100; i++) assertEquals(hash(i), cache.at(tip, i, lookup).hash());
        assertEquals(9950, reads.get());
        assertNull(cache.at(tip, 10_001, lookup));
    }

    @Test
    void sameHeightReorgAndRollbackInvalidateCachedAncestors() {
        var genesis = index(0, 0, hash(-1));
        var a = index(1, 1, genesis.hash()); var aTip = index(2, 2, a.hash());
        var b = index(3, 1, genesis.hash()); var bTip = index(4, 2, b.hash());
        var map = java.util.Map.of(genesis.hash(), genesis, a.hash(), a, aTip.hash(), aTip, b.hash(), b, bTip.hash(), bTip);
        var cache = new ActiveChainAncestors(10);
        assertEquals(a, cache.at(aTip, 1, map::get));
        assertEquals(b, cache.at(bTip, 1, map::get));
        assertEquals(genesis, cache.at(genesis, 0, map::get));
        assertEquals(a, cache.at(aTip, 1, map::get));
    }

    private static Hash256 hash(int n) { return new Hash256(ByteBuffer.allocate(32).putInt(n).array()); }
    private static BlockIndex index(int id, long height, Hash256 parent) {
        return new BlockIndex(hash(id), GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header(),
                height, parent, BigInteger.valueOf(height + 1));
    }
}