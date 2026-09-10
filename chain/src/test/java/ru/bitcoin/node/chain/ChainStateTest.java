package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChainStateTest {

    @Test
    void shouldNotPrepareUpdateWhenCandidateIsNotStronger() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        ChainState state =
                new ChainState(genesis);

        Map<Hash256, BlockIndex> indexes =
                Map.of(
                        genesis.hash(),
                        genesis
                );

        ChainUpdate update =
                state.prepareUpdate(
                        genesis,
                        indexes::get
                );

        assertNull(update);

        assertSame(
                genesis,
                state.activeTip()
        );
    }

    @Test
    void prepareUpdateShouldNotChangeActiveTip() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex child =
                child(
                        genesis,
                        1
                );

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        indexes.put(
                genesis.hash(),
                genesis
        );

        indexes.put(
                child.hash(),
                child
        );

        ChainState state =
                new ChainState(genesis);

        ChainUpdate update =
                state.prepareUpdate(
                        child,
                        indexes::get
                );

        assertNotNull(update);

        /*
         * Главное:
         *
         * prepareUpdate НЕ должен менять activeTip.
         */
        assertSame(
                genesis,
                state.activeTip()
        );

        assertSame(
                genesis,
                update.oldTip()
        );

        assertSame(
                child,
                update.newTip()
        );
    }

    @Test
    void commitShouldChangeActiveTip() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex child =
                child(
                        genesis,
                        1
                );

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        indexes.put(
                genesis.hash(),
                genesis
        );

        indexes.put(
                child.hash(),
                child
        );

        ChainState state =
                new ChainState(genesis);

        ChainUpdate update =
                state.prepareUpdate(
                        child,
                        indexes::get
                );

        assertNotNull(update);

        state.commit(update);

        assertSame(
                child,
                state.activeTip()
        );
    }

    @Test
    void shouldRejectStaleUpdate() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex first =
                child(
                        genesis,
                        1
                );

        BlockIndex second =
                child(
                        first,
                        2
                );

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        indexes.put(
                genesis.hash(),
                genesis
        );

        indexes.put(
                first.hash(),
                first
        );

        indexes.put(
                second.hash(),
                second
        );

        ChainState state =
                new ChainState(genesis);

        /*
         * Создаём update:
         *
         * genesis -> first
         */
        ChainUpdate staleUpdate =
                state.prepareUpdate(
                        first,
                        indexes::get
                );

        assertNotNull(staleUpdate);

        /*
         * Создаём более новый update:
         *
         * genesis -> second
         */
        ChainUpdate newerUpdate =
                state.prepareUpdate(
                        second,
                        indexes::get
                );

        assertNotNull(newerUpdate);

        /*
         * Сначала применяем более новый.
         */
        state.commit(newerUpdate);

        assertSame(
                second,
                state.activeTip()
        );

        /*
         * Теперь staleUpdate содержит oldTip=genesis,
         * но текущий activeTip уже second.
         *
         * Такой commit должен быть запрещён.
         */
        assertThrows(
                IllegalStateException.class,
                () -> state.commit(
                        staleUpdate
                )
        );

        assertSame(
                second,
                state.activeTip()
        );
    }

    @Test
    void shouldPrepareReorganizationWithoutApplyingIt() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        /*
         *        b
         *       / \
         *      c   d
         *          |
         *          e
         */

        BlockIndex c =
                child(b, 3);

        BlockIndex d =
                child(b, 4);

        BlockIndex e =
                child(d, 5);

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

        ChainState state =
                new ChainState(c);

        ChainUpdate update =
                state.prepareUpdate(
                        e,
                        indexes::get
                );

        assertNotNull(update);

        /*
         * Пока ничего не применили.
         */
        assertSame(
                c,
                state.activeTip()
        );

        ReorganizationPlan plan =
                update.reorganizationPlan();

        assertEquals(
                b.hash(),
                plan.commonAncestor().hash()
        );

        assertEquals(
                1,
                plan.blocksToDisconnect().size()
        );

        assertEquals(
                c.hash(),
                plan.blocksToDisconnect()
                        .get(0)
                        .hash()
        );

        assertEquals(
                2,
                plan.blocksToConnect().size()
        );

        assertEquals(
                d.hash(),
                plan.blocksToConnect()
                        .get(0)
                        .hash()
        );

        assertEquals(
                e.hash(),
                plan.blocksToConnect()
                        .get(1)
                        .hash()
        );

        /*
         * Только после успешного применения
         * будущих storage/UTXO операций.
         */
        state.commit(update);

        assertSame(
                e,
                state.activeTip()
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