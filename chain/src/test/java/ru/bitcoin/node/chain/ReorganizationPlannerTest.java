package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReorganizationPlannerTest {

    @Test
    void shouldBuildReorganizationPlan() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        /*
         *          b
         *         / \
         *        c   d
         *        |   |
         *        e   f
         *            |
         *            g
         */

        BlockIndex c =
                child(b, 3);

        BlockIndex e =
                child(c, 4);

        BlockIndex d =
                child(b, 5);

        BlockIndex f =
                child(d, 6);

        BlockIndex g =
                child(f, 7);

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
                        f,
                        g
                }) {

            indexes.put(
                    index.hash(),
                    index
            );
        }

        ReorganizationPlan plan =
                ReorganizationPlanner.plan(
                        e,
                        g,
                        indexes::get
                );

        assertEquals(
                b.hash(),
                plan.commonAncestor().hash()
        );

        /*
         * Откатываем:
         *
         * e -> c
         */
        assertEquals(
                2,
                plan.blocksToDisconnect().size()
        );

        assertEquals(
                e.hash(),
                plan.blocksToDisconnect()
                        .get(0)
                        .hash()
        );

        assertEquals(
                c.hash(),
                plan.blocksToDisconnect()
                        .get(1)
                        .hash()
        );

        /*
         * Подключаем:
         *
         * d -> f -> g
         */
        assertEquals(
                3,
                plan.blocksToConnect().size()
        );

        assertEquals(
                d.hash(),
                plan.blocksToConnect()
                        .get(0)
                        .hash()
        );

        assertEquals(
                f.hash(),
                plan.blocksToConnect()
                        .get(1)
                        .hash()
        );

        assertEquals(
                g.hash(),
                plan.blocksToConnect()
                        .get(2)
                        .hash()
        );
    }

    @Test
    void sameTipShouldProduceEmptyPlan() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        Map<Hash256, BlockIndex> indexes =
                Map.of(
                        genesis.hash(),
                        genesis
                );

        ReorganizationPlan plan =
                ReorganizationPlanner.plan(
                        genesis,
                        genesis,
                        indexes::get
                );

        assertEquals(
                genesis.hash(),
                plan.commonAncestor().hash()
        );

        assertEquals(
                0,
                plan.blocksToDisconnect().size()
        );

        assertEquals(
                0,
                plan.blocksToConnect().size()
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