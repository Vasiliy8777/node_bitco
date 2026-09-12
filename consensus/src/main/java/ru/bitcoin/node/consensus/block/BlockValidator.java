package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.consensus.transaction.TransactionValidator;
import ru.bitcoin.node.crypto.merkle.MerkleRootResult;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.List;

public final class BlockValidator {

    private BlockValidator() {
    }

    public static void validateStructure(Block block) {

        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        List<Transaction> transactions =
                block.transactions();

        /*
         * Bitcoin block must contain
         * at least the coinbase transaction.
         */
        if (transactions.isEmpty()) {
            throw new BlockValidationException(
                    "Block must contain at least one transaction"
            );
        }

        /*
         * First transaction must be coinbase.
         */
        if (!transactions.getFirst().isCoinbase()) {
            throw new BlockValidationException(
                    "First transaction must be coinbase"
            );
        }

        /*
         * No other transaction may be coinbase.
         */
        for (int i = 1; i < transactions.size(); i++) {

            if (transactions.get(i).isCoinbase()) {
                throw new BlockValidationException(
                        "Block contains multiple coinbase transactions"
                );
            }
        }

        for (Transaction transaction :
                transactions) {

            try {
                TransactionValidator.validateBasic(
                        transaction
                );
            } catch (TransactionValidationException e) {

                throw new BlockValidationException(
                        "Block contains invalid transaction: "
                                + e.getMessage()
                );
            }
        }

        long blockWeight =
                BlockWeight.calculate(
                        block
                );

        if (blockWeight
                > BlockWeight.MAX_BLOCK_WEIGHT) {

            throw new BlockValidationException(
                    "Block weight exceeds MAX_BLOCK_WEIGHT: "
                            + blockWeight
                            + " > "
                            + BlockWeight.MAX_BLOCK_WEIGHT
            );
        }

        validateMerkleRoot(
                block
        );

        validateMerkleRoot(
                block
        );
    }

    private static void validateMerkleRoot(
            Block block
    ) {
        List<Hash256> transactionIds =
                block.transactions()
                        .stream()
                        .map(Transaction::txId)
                        .toList();

        MerkleRootResult result =
                MerkleTree.calculateRootWithMutation(
                        transactionIds
                );

        if (!result.root().equals(
                block.header().merkleRoot()
        )) {
            throw new BlockValidationException(
                    "Invalid block Merkle root. "
                            + "Expected: "
                            + block.header()
                            .merkleRoot()
                            .toDisplayHex()
                            + ", calculated: "
                            + result.root()
                            .toDisplayHex()
            );
        }

        if (result.mutated()) {
            throw new BlockValidationException(
                    "Block contains mutated Merkle tree"
            );
        }
    }
}