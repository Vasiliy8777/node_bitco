package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.consensus.transaction.TransactionValidator;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BlockValidatorTest {
    private static Transaction coinbase() {

        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                new byte[]{0x01, 0x01},
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
    private static Transaction normalTransaction() {

        return new Transaction(
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
                                TxIn.FINAL_SEQUENCE
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
    }
    private static Block block(
            List<Transaction> transactions
    ) {

        Hash256 merkleRoot =
                transactions.isEmpty()
                        ? Hash256.fromDisplayHex(
                        "00".repeat(32)
                )
                        : MerkleTree.calculateRoot(
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
                        new UInt32(1_700_000_000L),
                        new UInt32(0x207fffffL),
                        new UInt32(0)
                );

        return new Block(
                header,
                transactions
        );
    }
    @Test
    void shouldRejectMultipleCoinbaseTransactions() {

        Block block =
                block(
                        List.of(
                                coinbase(),
                                coinbase()
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldRejectBlockWithoutTransactions() {

        Block block =
                block(
                        List.of()
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldRejectInvalidMerkleRoot() {

        Transaction coinbase =
                coinbase();

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),

                        /*
                         * Заведомо не txid coinbase.
                         */
                        Hash256.fromDisplayHex(
                                "ff".repeat(32)
                        ),

                        new UInt32(1_700_000_000L),
                        new UInt32(0x207fffffL),
                        new UInt32(0)
                );

        Block block =
                new Block(
                        header,
                        List.of(
                                coinbase
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldAcceptStructurallyValidBlock() {

        Block block =
                block(
                        List.of(
                                coinbase(),
                                normalTransaction()
                        )
                );

        assertDoesNotThrow(
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldRejectBlockWithoutCoinbaseFirst() {

        Block block =
                block(
                        List.of(
                                normalTransaction()
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldRejectMutatedMerkleTree() {

        Transaction coinbase =
                coinbase();

        Transaction tx1 =
                normalTransaction();

        Transaction tx2 =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                Hash256.fromDisplayHex(
                                                        "22".repeat(32)
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        2_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        List<Transaction> transactions =
                List.of(
                        coinbase,
                        tx1,
                        tx2,
                        tx2
                );

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
                        new UInt32(1_700_000_000L),
                        new UInt32(0x207fffffL),
                        new UInt32(0)
                );

        Block block =
                new Block(
                        header,
                        transactions
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
    @Test
    void shouldRejectBlockContainingInvalidTransaction() {

        Transaction invalidTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                Hash256.fromDisplayHex(
                                                        "22".repeat(32)
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        Money.MAX_MONEY + 1,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        Block block =
                block(
                        List.of(
                                coinbase(),
                                invalidTransaction
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(
                                block
                        )
        );
    }
    @Test
    void shouldRejectBlockWithInvalidCoinbaseScriptSig() {

        Transaction invalidCoinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{0x01},
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

        Block block =
                block(
                        List.of(
                                invalidCoinbase
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(
                                block
                        )
        );
    }
    private static Transaction largeTransaction(
            int id
    ) {
        byte[] hashBytes =
                new byte[32];

        hashBytes[0] =
                (byte) id;

        hashBytes[1] =
                (byte) (id >>> 8);

        hashBytes[2] =
                (byte) (id >>> 16);

        hashBytes[3] =
                (byte) (id >>> 24);

        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        new Hash256(
                                                hashBytes
                                        ),
                                        new UInt32(0)
                                ),
                                new byte[0],
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                1_000L,
                                new byte[300_000]
                        )
                ),
                new UInt32(0)
        );
    }
    @Test
    void shouldRejectBlockAboveMaximumWeight() {

        Block block =
                block(
                        List.of(
                                coinbase(),
                                largeTransaction(1),
                                largeTransaction(2),
                                largeTransaction(3),
                                largeTransaction(4)
                        )
                );

        assertTrue(
                BlockWeight.calculate(block)
                        > BlockWeight.MAX_BLOCK_WEIGHT
        );

        assertThrows(
                BlockValidationException.class,
                () -> BlockValidator
                        .validateStructure(block)
        );
    }
}
