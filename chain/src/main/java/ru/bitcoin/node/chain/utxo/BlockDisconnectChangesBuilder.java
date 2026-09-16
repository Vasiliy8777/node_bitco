package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

import java.util.ArrayList;
import java.util.List;

public final class BlockDisconnectChangesBuilder {

    private BlockDisconnectChangesBuilder() {
    }

    public static UtxoChanges build(
            Block block,
            BlockUndoData undoData
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }

        List<Transaction> transactions =
                block.transactions();

        if (transactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Block must contain at least coinbase transaction"
            );
        }

        int expectedUndoCount =
                transactions.size() - 1;

        List<TransactionUndo> transactionUndos =
                undoData.transactions();

        if (transactionUndos.size()
                != expectedUndoCount) {

            throw new IllegalStateException(
                    "Undo transaction count mismatch: expected "
                            + expectedUndoCount
                            + ", actual "
                            + transactionUndos.size()
            );
        }

        /*
         * spentOutputs здесь означает:
         *
         * UTXO, которые необходимо DELETE
         * во время rollback.
         *
         * Это все outputs, созданные блоком.
         */
        List<OutPoint> outputsToDelete =
                new ArrayList<>();

        /*
         * createdOutputs здесь означает:
         *
         * UTXO, которые необходимо PUT обратно
         * во время rollback.
         */
        List<CreatedUtxo> outputsToRestore =
                new ArrayList<>();

        collectCreatedOutputs(
                transactions,
                outputsToDelete
        );

        collectSpentOutputsForRestore(
                transactions,
                transactionUndos,
                outputsToRestore
        );

        return new UtxoChanges(
                outputsToDelete,
                outputsToRestore
        );
    }

    private static void collectCreatedOutputs(
            List<Transaction> transactions,
            List<OutPoint> outputsToDelete
    ) {
        for (Transaction transaction
                : transactions) {

            for (int outputIndex = 0;
                 outputIndex < transaction.outputs().size();
                 outputIndex++) {

                byte[] script = transaction.outputs().get(outputIndex).scriptPubKey();
                if (ru.bitcoin.node.script.UnspendableScript.isUnspendable(script)) continue;

                OutPoint outPoint =
                        new OutPoint(
                                transaction.txId(),
                                new UInt32(outputIndex)
                        );

                outputsToDelete.add(
                        outPoint
                );
            }
        }
    }

    private static void collectSpentOutputsForRestore(
            List<Transaction> transactions,
            List<TransactionUndo> transactionUndos,
            List<CreatedUtxo> outputsToRestore
    ) {
        /*
         * transactions[0] = coinbase
         *
         * Поэтому:
         *
         * transactions[1] <-> transactionUndos[0]
         * transactions[2] <-> transactionUndos[1]
         * ...
         */
        for (int transactionIndex = 1;
             transactionIndex < transactions.size();
             transactionIndex++) {

            Transaction transaction =
                    transactions.get(
                            transactionIndex
                    );

            TransactionUndo transactionUndo =
                    transactionUndos.get(
                            transactionIndex - 1
                    );

            List<TxIn> inputs =
                    transaction.inputs();

            List<StoredUtxo> spentOutputs =
                    transactionUndo.spentOutputs();

            if (spentOutputs.size()
                    != inputs.size()) {

                throw new IllegalStateException(
                        "Undo input count mismatch for transaction "
                                + transaction.txId().toDisplayHex()
                                + ": expected "
                                + inputs.size()
                                + ", actual "
                                + spentOutputs.size()
                );
            }

            for (int inputIndex = 0;
                 inputIndex < inputs.size();
                 inputIndex++) {

                TxIn input =
                        inputs.get(
                                inputIndex
                        );

                StoredUtxo previousUtxo =
                        spentOutputs.get(
                                inputIndex
                        );

                outputsToRestore.add(
                        new CreatedUtxo(
                                input.previousOutput(),
                                previousUtxo
                        )
                );
            }
        }
    }
}
