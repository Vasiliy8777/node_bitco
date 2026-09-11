package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.ArrayList;
import java.util.List;

public final class BlockConnectChangesBuilder {

    private BlockConnectChangesBuilder() {
    }

    public static BlockConnectChanges build(
            Block block,
            long blockHeight,
            UtxoStore utxoStore
    ) {
        if (utxoStore == null) {
            throw new IllegalArgumentException(
                    "utxoStore must not be null"
            );
        }

        UtxoOverlay overlay =
                new UtxoOverlay(
                        utxoStore
                );

        BlockUndoData undoData =
                apply(
                        block,
                        blockHeight,
                        overlay
                );

        return new BlockConnectChanges(
                overlay.changes(),
                undoData
        );
    }

    public static BlockUndoData apply(
            Block block,
            long blockHeight,
            UtxoOverlay overlay
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (overlay == null) {
            throw new IllegalArgumentException(
                    "overlay must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        List<Transaction> transactions =
                block.transactions();

        if (transactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Block must contain at least one transaction"
            );
        }

        List<TransactionUndo> transactionUndos =
                new ArrayList<>();

        for (int transactionIndex = 0;
             transactionIndex < transactions.size();
             transactionIndex++) {

            Transaction transaction =
                    transactions.get(
                            transactionIndex
                    );

            boolean coinbase =
                    transactionIndex == 0;

            if (!coinbase) {

                TransactionUndo transactionUndo =
                        spendInputs(
                                transaction,
                                overlay
                        );

                transactionUndos.add(
                        transactionUndo
                );
            }

            createOutputs(
                    transaction,
                    blockHeight,
                    coinbase,
                    overlay
            );
        }

        return new BlockUndoData(
                transactionUndos
        );
    }

    private static TransactionUndo spendInputs(
            Transaction transaction,
            UtxoOverlay overlay
    ) {

        List<StoredUtxo> spentOutputs =
                new ArrayList<>();

        for (TxIn input
                : transaction.inputs()) {

            StoredUtxo spentUtxo =
                    overlay.spend(
                            input.previousOutput()
                    );

            /*
             * Порядок принципиален:
             *
             * inputs[0] <-> spentOutputs[0]
             * inputs[1] <-> spentOutputs[1]
             * ...
             *
             * Именно на это потом опирается
             * BlockDisconnectChangesBuilder.
             */
            spentOutputs.add(
                    spentUtxo
            );
        }

        return new TransactionUndo(
                spentOutputs
        );
    }

    private static void createOutputs(
            Transaction transaction,
            long blockHeight,
            boolean coinbase,
            UtxoOverlay overlay
    ) {

        List<TxOut> outputs =
                transaction.outputs();

        for (int outputIndex = 0;
             outputIndex < outputs.size();
             outputIndex++) {

            TxOut output =
                    outputs.get(
                            outputIndex
                    );

            OutPoint outPoint =
                    new OutPoint(
                            transaction.txId(),
                            new UInt32(
                                    outputIndex
                            )
                    );

            StoredUtxo utxo =
                    new StoredUtxo(
                            output.value(),
                            output.scriptPubKey(),
                            blockHeight,
                            coinbase
                    );

            overlay.put(
                    outPoint,
                    utxo
            );
        }
    }
}