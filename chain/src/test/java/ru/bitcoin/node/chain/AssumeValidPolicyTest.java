package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssumeValidPolicyTest {
    @Test
    void skipsOnlyOldAncestorOfAssumedAndBestHeaderChains() {
        NetworkParameters parameters = NetworkParametersRegistry.regtest();
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        BlockIndex genesis = block(null, 0, 0, indexes);
        BlockIndex cursor = genesis;
        BlockIndex assumed = null;
        BlockIndex boundary = null;
        for (int height = 1; height <= 2017; height++) {
            cursor = block(cursor, height, height, indexes);
            if (height == 1000) assumed = cursor;
            if (height == 2016) boundary = cursor;
        }
        BlockIndex best = cursor;
        BlockIndex assumedBlock = assumed;
        AssumeValidPolicy policy = new AssumeValidPolicy(indexes::get, () -> best, parameters, assumedBlock.hash());

        assertFalse(policy.shouldVerifyScripts(genesis), "older than two proof-equivalent weeks may skip scripts");
        assertTrue(policy.shouldVerifyScripts(assumed), "assumevalid block itself is too recent relative to this best header");
        assertTrue(policy.shouldVerifyScripts(boundary), "blocks outside the assumevalid ancestry must still verify");
    }

    @Test
    void zeroAssumeValidAlwaysVerifies() {
        NetworkParameters parameters = NetworkParametersRegistry.regtest();
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        BlockIndex genesis = block(null, 0, 0, indexes);
        AssumeValidPolicy policy = new AssumeValidPolicy(indexes::get, () -> genesis, parameters,
                new Hash256(new byte[32]));
        assertTrue(policy.shouldVerifyScripts(genesis));
    }

    @Test
    void missingAssumedHeaderAlwaysVerifies() {
        NetworkParameters parameters = NetworkParametersRegistry.regtest();
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        BlockIndex genesis = block(null, 0, 0, indexes);
        AssumeValidPolicy policy = new AssumeValidPolicy(indexes::get, () -> genesis, parameters,
                Hash256.fromDisplayHex("01".repeat(32)));
        assertTrue(policy.shouldVerifyScripts(genesis));
    }

    @Test
    void usesAncestorLookupInsteadOfLinearParentWalkWhenAvailable() {
        NetworkParameters parameters = NetworkParametersRegistry.regtest();
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        BlockIndex genesis = block(null, 0, 0, indexes);
        BlockIndex cursor = genesis;
        BlockIndex assumed = null;
        for (int height = 1; height <= 2300; height++) {
            cursor = block(cursor, height, height, indexes);
            if (height == 1000) assumed = cursor;
        }
        BlockIndex best = cursor;
        BlockIndex assumedBlock = assumed;
        int[] ancestorCalls = {0};
        int[] indexReads = {0};

        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            @Override
            public BlockIndex find(Hash256 hash) {
                indexReads[0]++;
                return indexes.get(hash);
            }

            @Override
            public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                ancestorCalls[0]++;
                BlockIndex current = index;
                while (current.height() > targetHeight) {
                    current = indexes.get(current.previousBlockHash());
                    if (current == null) {
                        throw new IllegalStateException("Missing test ancestor");
                    }
                }
                return current;
            }
        };

        AssumeValidPolicy policy = new AssumeValidPolicy(
                lookup, () -> best, parameters, assumedBlock.hash());

        assertFalse(policy.shouldVerifyScripts(genesis));
        assertTrue(indexReads[0] <= 129,
                "a proven best chain must reuse the assumed chain window instead of reading it twice");
        assertEquals(2, ancestorCalls[0],
                "assumed-valid and best-header ancestry must use BlockIndexAncestorLookup");

        BlockIndex heightOne = indexes.values().stream()
                .filter(index -> index.height() == 1)
                .findFirst()
                .orElseThrow();
        assertFalse(policy.shouldVerifyScripts(heightOne));
        assertEquals(2, ancestorCalls[0],
                "consecutive IBD candidates inside the ancestry window must reuse the exact cached proof");
        for (int height = 2; height < 256; height++) {
            final int candidateHeight = height;
            var candidate = indexes.values().stream().filter(i -> i.height() == candidateHeight).findFirst().orElseThrow();
            assertFalse(policy.shouldVerifyScripts(candidate));
        }
        assertEquals(3, ancestorCalls[0], "only the assumed-chain window needs refilling at height 128");
        assertTrue(indexReads[0] <= 512, "best-chain membership must not duplicate historical reads");
    }

    @Test
    void cachedBestMembershipMatchesIndependentChecksAcrossHeaderForks() {
        var parameters = NetworkParametersRegistry.regtest();
        Map<Hash256, BlockIndex> indexes = new HashMap<>();
        var main = new java.util.ArrayList<BlockIndex>();
        main.add(block(null, 0, 0, indexes));
        for (int height = 1; height <= 3100; height++) {
            main.add(block(main.getLast(), height, height, indexes));
        }
        var fork = main.get(500);
        var forkCandidates = new java.util.ArrayList<BlockIndex>();
        for (int height = 501; height <= 3100; height++) {
            fork = block(fork, height, 10000 + height, indexes);
            if (height == 501 || height == 1000) forkCandidates.add(fork);
        }
        var best = new java.util.concurrent.atomic.AtomicReference<>(main.getLast());
        BlockIndexAncestorLookup skip = new BlockIndexAncestorLookup() {
            public BlockIndex find(Hash256 hash) { return indexes.get(hash); }
            public BlockIndex ancestor(BlockIndex index, long height) {
                while (index.height() > height) index = indexes.get(index.previousBlockHash());
                return index;
            }
        };
        var optimized = new AssumeValidPolicy(skip, best::get, parameters, main.get(1000).hash());
        // The plain lookup takes the original two independent parent walks,
        // serving as an oracle unrelated to transitive-proof caching.
        var reference = new AssumeValidPolicy(indexes::get, best::get, parameters, main.get(1000).hash());
        for (var tip : java.util.List.of(main.getLast(), fork, main.get(600), main.getLast())) {
            best.set(tip);
            for (int height : new int[]{0, 127, 128, 499, 500, 501, 999, 1000, 1001, 2016}) {
                var candidate = main.get(height);
                assertEquals(reference.shouldVerifyScripts(candidate), optimized.shouldVerifyScripts(candidate),
                        "candidate=" + height + " best=" + tip.hash().toDisplayHex());
            }
            for (var candidate : forkCandidates) {
                assertEquals(reference.shouldVerifyScripts(candidate), optimized.shouldVerifyScripts(candidate));
            }
        }
    }

    private static BlockIndex block(BlockIndex parent, long height, long nonce, Map<Hash256, BlockIndex> indexes) {
        Hash256 zero = new Hash256(new byte[32]);
        BlockHeader header = new BlockHeader(1, parent == null ? zero : parent.hash(), zero,
                new UInt32(1_600_000_000L + height * 600L), new UInt32(0x207fffffL), new UInt32(nonce));
        BlockIndex index = parent == null ? BlockIndexFactory.createGenesis(header) : BlockIndexFactory.createChild(parent, header);
        indexes.put(index.hash(), index);
        return index;
    }
}
