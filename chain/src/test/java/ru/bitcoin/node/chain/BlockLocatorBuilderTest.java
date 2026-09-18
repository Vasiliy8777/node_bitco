package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLocatorBuilderTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldBuildBitcoinCoreCompatibleLocator() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        GenesisBlockFactory.create(
                                REGTEST
                        ).header()
                );

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        indexes.put(
                genesis.hash(),
                genesis
        );

        BlockIndex tip =
                genesis;

        for (int height = 1;
             height <= 100;
             height++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            tip.hash(),
                            hashForHeight(height),
                            new UInt32(
                                    1_700_000_000L + height
                            ),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    height
                            )
                    );

            tip =
                    BlockIndexFactory.createChild(
                            tip,
                            header
                    );

            indexes.put(
                    tip.hash(),
                    tip
            );
        }

        BlockIndexLookup lookup =
                indexes::get;

        BlockLocatorBuilder builder =
                new BlockLocatorBuilder(
                        lookup
                );

        List<Hash256> locator =
                builder.build(
                        tip
                );

        List<Long> heights =
                locator.stream()
                        .map(indexes::get)
                        .map(BlockIndex::height)
                        .toList();

        assertEquals(
                List.of(
                        100L,
                        99L,
                        98L,
                        97L,
                        96L,
                        95L,
                        94L,
                        93L,
                        92L,
                        91L,
                        90L,
                        89L,
                        87L,
                        83L,
                        75L,
                        59L,
                        27L,
                        0L
                ),
                heights
        );
    }

    @Test
    void shouldReturnOnlyGenesisForGenesisTip() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        GenesisBlockFactory.create(
                                REGTEST
                        ).header()
                );

        BlockLocatorBuilder builder =
                new BlockLocatorBuilder(
                        hash -> genesis.hash()
                                .equals(hash)
                                ? genesis
                                : null
                );

        assertEquals(
                List.of(
                        genesis.hash()
                ),
                builder.build(
                        genesis
                )
        );
    }

    private static Hash256 hashForHeight(
            int height
    ) {
        byte[] bytes =
                new byte[Hash256.LENGTH];

        bytes[0] =
                (byte) height;

        bytes[1] =
                (byte) (height >>> 8);

        bytes[2] =
                (byte) (height >>> 16);

        bytes[3] =
                (byte) (height >>> 24);

        return new Hash256(
                bytes
        );
    }
    @Test
    void shouldRejectMissingAncestor() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        GenesisBlockFactory.create(
                                REGTEST
                        ).header()
                );

        BlockHeader childHeader =
                new BlockHeader(
                        4,
                        genesis.hash(),
                        hashForHeight(1),
                        new UInt32(
                                1_700_000_001L
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                1
                        )
                );

        BlockIndex child =
                BlockIndexFactory.createChild(
                        genesis,
                        childHeader
                );

        /*
         * Lookup deliberately does not contain
         * the genesis parent.
         */
        BlockIndexLookup lookup =
                hash -> null;

        BlockLocatorBuilder builder =
                new BlockLocatorBuilder(
                        lookup
                );

        assertThrows(
                IllegalStateException.class,
                () -> builder.build(
                        child
                )
        );
    }

    @Test
    void shouldRejectAncestorWithInvalidHeight() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        GenesisBlockFactory.create(
                                REGTEST
                        ).header()
                );

        BlockHeader firstHeader =
                new BlockHeader(
                        4,
                        genesis.hash(),
                        hashForHeight(1),
                        new UInt32(
                                1_700_000_001L
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                1
                        )
                );

        BlockIndex first =
                BlockIndexFactory.createChild(
                        genesis,
                        firstHeader
                );

        BlockHeader secondHeader =
                new BlockHeader(
                        4,
                        first.hash(),
                        hashForHeight(2),
                        new UInt32(
                                1_700_000_002L
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                2
                        )
                );

        BlockIndex second =
                BlockIndexFactory.createChild(
                        first,
                        secondHeader
                );

        /*
         * second expects a parent at height 1,
         * but lookup deliberately returns genesis
         * at height 0.
         */
        BlockIndexLookup lookup =
                hash -> second.previousBlockHash()
                        .equals(hash)
                        ? genesis
                        : null;

        BlockLocatorBuilder builder =
                new BlockLocatorBuilder(
                        lookup
                );

        assertThrows(
                IllegalStateException.class,
                () -> builder.build(
                        second
                )
        );
    }
}