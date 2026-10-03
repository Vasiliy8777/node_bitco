package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Bip34AncestryCacheTest {

    @Test
    void directDescendantsInheritProofWithoutRepeatingFullLookup() {
        BlockIndex genesis = BlockIndexFactory.createGenesis(genesisHeader());
        BlockIndex first = child(genesis, 1);
        BlockIndex second = child(first, 2);
        BlockIndex third = child(second, 3);
        AtomicInteger fullProofs = new AtomicInteger();
        Bip34AncestryCache cache = new Bip34AncestryCache();

        assertTrue(cache.prove(first, () -> {
            fullProofs.incrementAndGet();
            return true;
        }));
        assertTrue(cache.prove(second, () -> {
            fullProofs.incrementAndGet();
            return true;
        }));
        assertTrue(cache.prove(third, () -> {
            fullProofs.incrementAndGet();
            return true;
        }));

        assertEquals(1, fullProofs.get());
        assertEquals(1, cache.diagnosticSnapshot().fullProofs());
        assertEquals(2, cache.diagnosticSnapshot().inheritedProofs());
    }

    @Test
    void forkMustRunFullProofBeforeMovingCacheToNewBranch() {
        BlockIndex genesis = BlockIndexFactory.createGenesis(genesisHeader());
        BlockIndex a = child(genesis, 1);
        BlockIndex a2 = child(a, 2);
        BlockIndex fork = child(genesis, 99);
        AtomicInteger fullProofs = new AtomicInteger();
        Bip34AncestryCache cache = new Bip34AncestryCache();

        assertTrue(cache.prove(a, () -> {
            fullProofs.incrementAndGet();
            return true;
        }));
        assertTrue(cache.prove(a2, () -> {
            fullProofs.incrementAndGet();
            return true;
        }));
        assertFalse(cache.prove(fork, () -> {
            fullProofs.incrementAndGet();
            return false;
        }));

        assertEquals(2, fullProofs.get());
        assertEquals(2, cache.diagnosticSnapshot().fullProofs());
        assertEquals(1, cache.diagnosticSnapshot().inheritedProofs());
    }

    private static BlockIndex child(BlockIndex parent, long nonce) {
        return BlockIndexFactory.createChild(parent, new BlockHeader(
                1, parent.hash(), Hash256.fromDisplayHex(String.format("%064x", nonce)),
                new UInt32(1231006505L + parent.height() * 600L + 600L),
                new UInt32(0x1D00FFFFL), new UInt32(nonce)));
    }

    private static BlockHeader genesisHeader() {
        return new BlockHeader(
                1,
                Hash256.fromDisplayHex("00".repeat(32)),
                Hash256.fromDisplayHex("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"),
                new UInt32(1231006505L), new UInt32(0x1D00FFFFL), new UInt32(2083236893L));
    }
}
