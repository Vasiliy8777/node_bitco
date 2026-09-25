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

    private static BlockIndex block(BlockIndex parent, long height, long nonce, Map<Hash256, BlockIndex> indexes) {
        Hash256 zero = new Hash256(new byte[32]);
        BlockHeader header = new BlockHeader(1, parent == null ? zero : parent.hash(), zero,
                new UInt32(1_600_000_000L + height * 600L), new UInt32(0x207fffffL), new UInt32(nonce));
        BlockIndex index = parent == null ? BlockIndexFactory.createGenesis(header) : BlockIndexFactory.createChild(parent, header);
        indexes.put(index.hash(), index);
        return index;
    }
}
