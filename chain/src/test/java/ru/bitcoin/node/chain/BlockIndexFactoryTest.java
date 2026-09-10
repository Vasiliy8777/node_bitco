package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockIndexFactoryTest {

    @Test
    void shouldCreateGenesisIndex() {

        BlockHeader genesis =
                genesisHeader();

        BlockIndex index =
                BlockIndexFactory.createGenesis(
                        genesis
                );

        assertEquals(
                0,
                index.height()
        );

        assertEquals(
                genesis.hash(),
                index.hash()
        );

        assertEquals(
                ChainWork.blockWork(
                        genesis.bits().value()
                ),
                index.chainWork()
        );
    }

    @Test
    void shouldCreateChildIndex() {

        BlockHeader genesis =
                genesisHeader();

        BlockIndex parent =
                BlockIndexFactory.createGenesis(
                        genesis
                );

        BlockHeader child =
                new BlockHeader(
                        1,
                        parent.hash(),
                        Hash256.fromDisplayHex(
                                "0000000000000000000000000000000000000000000000000000000000000001"
                        ),
                        new UInt32(1231007105L),
                        genesis.bits(),
                        new UInt32(1)
                );

        BlockIndex childIndex =
                BlockIndexFactory.createChild(
                        parent,
                        child
                );

        BigInteger expectedChainWork =
                parent.chainWork().add(
                        ChainWork.blockWork(
                                child.bits().value()
                        )
                );

        assertEquals(
                1,
                childIndex.height()
        );

        assertEquals(
                parent.hash(),
                childIndex.previousBlockHash()
        );

        assertEquals(
                expectedChainWork,
                childIndex.chainWork()
        );
    }

    @Test
    void shouldRejectWrongParent() {

        BlockHeader genesis =
                genesisHeader();

        BlockIndex parent =
                BlockIndexFactory.createGenesis(
                        genesis
                );

        BlockHeader invalidChild =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                        ),
                        Hash256.fromDisplayHex(
                                "0000000000000000000000000000000000000000000000000000000000000001"
                        ),
                        new UInt32(1231007105L),
                        genesis.bits(),
                        new UInt32(1)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BlockIndexFactory.createChild(
                        parent,
                        invalidChild
                )
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
