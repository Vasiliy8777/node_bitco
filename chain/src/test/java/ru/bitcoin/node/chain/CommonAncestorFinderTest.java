package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonAncestorFinderTest {

    @Test
    void shouldFindCommonAncestorOfForks() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        /*
         * fork:
         *
         *       b
         *      / \
         *     c   d
         *     |   |
         *     e   f
         */

        BlockIndex c =
                child(b, 3);

        BlockIndex e =
                child(c, 4);

        BlockIndex d =
                child(b, 5);

        BlockIndex f =
                child(d, 6);

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        for (BlockIndex index :
                new BlockIndex[]{
                        genesis,
                        a,
                        b,
                        c,
                        d,
                        e,
                        f
                }) {

            indexes.put(
                    index.hash(),
                    index
            );
        }

        BlockIndexLookup lookup =
                indexes::get;

        BlockIndex ancestor =
                CommonAncestorFinder.find(
                        e,
                        f,
                        lookup
                );

        assertEquals(
                b.hash(),
                ancestor.hash()
        );
    }

    @Test
    void shouldHandleDifferentHeights() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        BlockIndex c =
                child(b, 3);

        /*
         *        b
         *       / \
         *      c   d
         *      |
         *      e
         */

        BlockIndex d =
                child(b, 4);

        BlockIndex e =
                child(c, 5);

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        for (BlockIndex index :
                new BlockIndex[]{
                        genesis,
                        a,
                        b,
                        c,
                        d,
                        e
                }) {

            indexes.put(
                    index.hash(),
                    index
            );
        }

        BlockIndex ancestor =
                CommonAncestorFinder.find(
                        e,
                        d,
                        indexes::get
                );

        assertEquals(
                b.hash(),
                ancestor.hash()
        );
    }

    private static BlockIndex child(
            BlockIndex parent,
            long nonce
    ) {

        BlockHeader header =
                new BlockHeader(
                        1,
                        parent.hash(),
                        Hash256.fromDisplayHex(
                                String.format(
                                        "%064x",
                                        nonce
                                )
                        ),
                        new UInt32(
                                1231006505L
                                        + parent.height() * 600
                                        + 600
                        ),
                        new UInt32(0x1D00FFFFL),
                        new UInt32(nonce)
                );

        return BlockIndexFactory.createChild(
                parent,
                header
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
}