package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockWeightTest {

    @Test
    void shouldCalculateLegacyBlockWeightAsFourTimesSize() {

        Transaction coinbase =
                coinbase();

        Block block =
                block(
                        List.of(
                                coinbase
                        )
                );

        long totalSize =
                ru.bitcoin.node.protocol.serialization
                        .BlockSerializer
                        .serialize(block)
                        .length;

        long weight =
                BlockWeight.calculate(
                        block
                );

        assertEquals(
                totalSize * 4L,
                weight
        );
    }

    @Test
    void shouldAcceptSmallBlockWeight() {

        Block block =
                block(
                        List.of(
                                coinbase()
                        )
                );

        assertTrue(
                BlockWeight.isWithinLimit(
                        block
                )
        );
    }

    private static Transaction coinbase() {

        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                new byte[]{
                                        0x01,
                                        0x01
                                },
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                5_000L,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }

    private static Block block(
            List<Transaction> transactions
    ) {

        Hash256 merkleRoot =
                MerkleTree.calculateRoot(
                        transactions.stream()
                                .map(Transaction::txId)
                                .toList()
                );

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        merkleRoot,
                        new UInt32(
                                1_700_000_000L
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(0)
                );

        return new Block(
                header,
                transactions
        );
    }
    @Test
    void shouldCalculateWitnessBlockWeightCorrectly() {

        Witness witness =
                new Witness(
                        List.of(
                                new byte[100]
                        )
                );

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                Hash256.fromDisplayHex(
                                                        "11".repeat(32)
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE,
                                        witness
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        Block block =
                block(
                        List.of(
                                coinbase(),
                                transaction
                        )
                );

        long strippedSize =
                BlockSerializer
                        .serializeLegacy(block)
                        .length;

        long totalSize =
                BlockSerializer
                        .serialize(block)
                        .length;

        long expectedWeight =
                strippedSize * 3L
                        + totalSize;

        assertEquals(
                expectedWeight,
                BlockWeight.calculate(block)
        );

        assertTrue(
                totalSize > strippedSize
        );
    }

}