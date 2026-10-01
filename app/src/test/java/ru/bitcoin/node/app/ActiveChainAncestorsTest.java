package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexAncestorLookup;
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
    void directTipExtensionPreservesHistoricalEntries() {
        var map = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = index(0, 0, hash(-1));
        map.put(tip.hash(), tip);
        for (int i = 1; i <= 100; i++) {
            tip = index(i, i, tip.hash());
            map.put(tip.hash(), tip);
        }
        var cache = new ActiveChainAncestors(128);
        var reads = new AtomicInteger();
        ru.bitcoin.node.chain.BlockIndexLookup lookup = hash -> { reads.incrementAndGet(); return map.get(hash); };

        assertEquals(hash(50), cache.at(tip, 50, lookup).hash());
        int afterWarmup = reads.get();

        BlockIndex next = index(101, 101, tip.hash());
        map.put(next.hash(), next);
        assertEquals(hash(50), cache.at(next, 50, lookup).hash());
        assertEquals(afterWarmup, reads.get(), "direct active-tip extension must retain historical ancestry cache");
    }

    @Test
    void multiBlockTipExtensionUsesBranchSafeAncestorProofAndPreservesCache() {
        var map = new HashMap<Hash256, BlockIndex>();
        BlockIndex oldTip = index(0, 0, hash(-1));
        map.put(oldTip.hash(), oldTip);
        for (int i = 1; i <= 100; i++) {
            oldTip = index(i, i, oldTip.hash());
            map.put(oldTip.hash(), oldTip);
        }
        BlockIndex newTip = oldTip;
        for (int i = 101; i <= 110; i++) {
            newTip = index(i, i, newTip.hash());
            map.put(newTip.hash(), newTip);
        }

        var cache = new ActiveChainAncestors(128);
        var parentReads = new AtomicInteger();
        var ancestorCalls = new AtomicInteger();
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            @Override public BlockIndex find(Hash256 hash) {
                parentReads.incrementAndGet();
                return map.get(hash);
            }
            @Override public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                ancestorCalls.incrementAndGet();
                BlockIndex cursor = index;
                while (cursor.height() > targetHeight) cursor = map.get(cursor.previousBlockHash());
                return cursor.height() == targetHeight ? cursor : null;
            }
        };

        assertEquals(hash(50), cache.at(oldTip, 50, lookup).hash());

        int afterWarmup = parentReads.get();
        int ancestorCallsAfterWarmup = ancestorCalls.get();

        assertEquals(hash(50), cache.at(newTip, 50, lookup).hash());

        assertEquals(
                afterWarmup,
                parentReads.get(),
                "proven descendant tip must retain historical entries"
        );

        assertEquals(
                ancestorCallsAfterWarmup + 1,
                ancestorCalls.get(),
                "tip jump should require exactly one additional branch-safe ancestry proof"
        );
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


    @Test
    void cacheMissUsesAncestorLookupWithoutLinearParentWalkOrCachePollution() {
        var map = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = index(0, 0, hash(-1));
        map.put(tip.hash(), tip);
        for (int i = 1; i <= 10_000; i++) {
            tip = index(i, i, tip.hash());
            map.put(tip.hash(), tip);
        }

        var cache = new ActiveChainAncestors(8);
        var findCalls = new AtomicInteger();
        var ancestorCalls = new AtomicInteger();
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            @Override public BlockIndex find(Hash256 hash) {
                findCalls.incrementAndGet();
                return map.get(hash);
            }

            @Override public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                ancestorCalls.incrementAndGet();
                BlockIndex cursor = index;
                // The fake lookup resolves directly so the test verifies that
                // ActiveChainAncestors itself does not perform the linear walk.
                while (cursor.height() > targetHeight) cursor = map.get(cursor.previousBlockHash());
                return cursor.height() == targetHeight ? cursor : null;
            }
        };

        assertEquals(hash(50), cache.at(tip, 50, lookup).hash());
        assertEquals(1, ancestorCalls.get());
        assertEquals(0, findCalls.get(), "cache miss must delegate to branch-safe ancestor lookup");

        assertEquals(hash(51), cache.at(tip, 51, lookup).hash());
        assertEquals(2, ancestorCalls.get());
        // Stage 24 recognizes the second consecutive historical miss as an ascending scan.
        // With cache capacity 8 it prefetches at most 7 new entries (51..57), reserving
        // one sparse-cache slot for the preceding anchor height 50. This avoids evicting the
        // exact entry that detected the ascending scan.
        // This is intentional bounded prefetch, not the old O(distance) walk from the tip.
        assertEquals(6, findCalls.get());

        int findsAfterPrefetch = findCalls.get();
        assertEquals(hash(50), cache.at(tip, 50, lookup).hash());
        assertEquals(2, ancestorCalls.get(), "exact cached height must remain a zero-read hit");
        assertEquals(findsAfterPrefetch, findCalls.get(),
                "exact cached height must not perform additional parent reads");
    }

    @Test
    void recentHotWindowIsNotEvictedBySparseHistoricalTraffic() {
        var map = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = index(0, 0, hash(-1));
        map.put(tip.hash(), tip);
        for (int i = 1; i <= 120; i++) {
            tip = index(i, i, tip.hash());
            map.put(tip.hash(), tip);
        }

        var cache = new ActiveChainAncestors(8);
        var ancestorCalls = new AtomicInteger();
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            @Override public BlockIndex find(Hash256 hash) { return map.get(hash); }
            @Override public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                ancestorCalls.incrementAndGet();
                BlockIndex cursor = index;
                while (cursor.height() > targetHeight) cursor = map.get(cursor.previousBlockHash());
                return cursor;
            }
        };

        // Advance the observed active tip one block at a time while deliberately
        // churning the sparse historical LRU with unrelated old heights.
        BlockIndex observed = map.get(hash(100));
        cache.at(observed, 1, lookup);
        for (int height = 101; height <= 120; height++) {
            observed = map.get(hash(height));
            cache.at(observed, 1 + ((height - 101) % 16), lookup);
        }

        int beforeRecentLookup = ancestorCalls.get();
        assertEquals(hash(115), cache.at(observed, 115, lookup).hash());
        assertEquals(beforeRecentLookup, ancestorCalls.get(),
                "sparse historical traffic must not punch holes in the recent active-chain window");
    }

    @Test
    void ascendingHistoricalMissesPrefetchDenseSliceOnce() {
        var map = new HashMap<Hash256, BlockIndex>();
        BlockIndex tip = index(0, 0, hash(-1));
        map.put(tip.hash(), tip);
        for (int i = 1; i <= 10_000; i++) {
            tip = index(i, i, tip.hash());
            map.put(tip.hash(), tip);
        }

        var cache = new ActiveChainAncestors(128);
        var findCalls = new AtomicInteger();
        var ancestorCalls = new AtomicInteger();
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            @Override public BlockIndex find(Hash256 hash) {
                findCalls.incrementAndGet();
                return map.get(hash);
            }

            @Override public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                ancestorCalls.incrementAndGet();
                BlockIndex cursor = index;
                while (cursor.height() > targetHeight) cursor = map.get(cursor.previousBlockHash());
                return cursor;
            }
        };

        assertEquals(hash(50), cache.at(tip, 50, lookup).hash());
        assertEquals(1, ancestorCalls.get());
        assertEquals(0, findCalls.get());

        // The second consecutive miss recognizes an ascending scan. With capacity 128,
        // heights 51..177 are materialized in one bounded reverse walk while height 50 remains cached.
        assertEquals(hash(51), cache.at(tip, 51, lookup).hash());
        assertEquals(2, ancestorCalls.get());
        assertEquals(126, findCalls.get());

        int ancestorsAfterPrefetch = ancestorCalls.get();
        int findsAfterPrefetch = findCalls.get();
        for (int height = 52; height <= 177; height++) {
            assertEquals(hash(height), cache.at(tip, height, lookup).hash());
        }
        assertEquals(ancestorsAfterPrefetch, ancestorCalls.get(),
                "prefetched ascending slice must avoid repeated skip-ancestor walks");
        assertEquals(findsAfterPrefetch, findCalls.get(),
                "prefetched ascending slice must be served without parent reads");
    }

    private static Hash256 hash(int n) { return new Hash256(ByteBuffer.allocate(32).putInt(n).array()); }
    private static BlockIndex index(int id, long height, Hash256 parent) {
        return new BlockIndex(hash(id), GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header(),
                height, parent, BigInteger.valueOf(height + 1));
    }
}
