package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.List;

public final class BlockDisconnectApplier {

    private BlockDisconnectApplier() {
    }

    public static void apply(
            Block block,
            BlockUndoData undoData,
            UtxoOverlay overlay
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

        if (overlay == null) {
            throw new IllegalArgumentException(
                    "overlay must not be null"
            );
        }

        List<Transaction> transactions =
                block.transactions();

        if (transactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "block must contain at least one transaction"
            );
        }

        List<TransactionUndo> transactionUndo =
                undoData.transactions();

        int expectedUndoCount =
                transactions.size() - 1;

        if (transactionUndo.size()
                != expectedUndoCount) {

            throw new IllegalStateException(
                    "Undo transaction count does not match block. "
                            + "Expected: "
                            + expectedUndoCount
                            + ", actual: "
                            + transactionUndo.size()
            );
        }

        /*
         * Bitcoin block disconnect:
         *
         * TXs MUST be undone in reverse order.
         */
        for (int txIndex =
             transactions.size() - 1;
             txIndex >= 0;
             txIndex--) {

            Transaction transaction =
                    transactions.get(txIndex);

            /*
             * First remove every output created
             * by this transaction.
             */
            removeCreatedOutputs(
                    transaction,
                    overlay
            );

            /*
             * Transaction 0 is coinbase.
             *
             * Coinbase has no previous UTXOs
             * that need restoring.
             */
            if (txIndex == 0) {
                continue;
            }

            TransactionUndo undo =
                    transactionUndo.get(
                            txIndex - 1
                    );

            restoreSpentOutputs(
                    transaction,
                    undo,
                    overlay
            );
        }
    }

    private static void removeCreatedOutputs(
            Transaction transaction,
            UtxoOverlay overlay
    ) {
        for (int outputIndex = 0;
             outputIndex
                     < transaction.outputs().size();
             outputIndex++) {

            byte[] script = transaction.outputs().get(outputIndex).scriptPubKey();
            if (script.length > 10_000 || (script.length > 0 && script[0] == 0x6a)) continue;

            OutPoint outPoint =
                    new OutPoint(
                            transaction.txId(),
                            new UInt32(outputIndex)
                    );

            overlay.spend(outPoint);
        }
    }

    private static void restoreSpentOutputs(
            Transaction transaction,
            TransactionUndo undo,
            UtxoOverlay overlay
    ) {
        List<StoredUtxo> spentOutputs =
                undo.spentOutputs();

        if (spentOutputs.size()
                != transaction.inputs().size()) {

            throw new IllegalStateException(
                    "Undo input count does not match transaction. "
                            + "Transaction: "
                            + transaction.txId()
                            .toDisplayHex()
                            + ", expected: "
                            + transaction.inputs().size()
                            + ", actual: "
                            + spentOutputs.size()
            );
        }

        for (int inputIndex = 0;
             inputIndex
                     < transaction.inputs().size();
             inputIndex++) {

            OutPoint previousOutput =
                    transaction.inputs()
                            .get(inputIndex)
                            .previousOutput();

            StoredUtxo previousUtxo =
                    spentOutputs.get(
                            inputIndex
                    );

            overlay.put(
                    previousOutput,
                    previousUtxo
            );
        }
    }
}
