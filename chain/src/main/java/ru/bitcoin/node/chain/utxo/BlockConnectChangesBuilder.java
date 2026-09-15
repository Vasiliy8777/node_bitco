package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.chain.AncestorMedianTimePastResolver;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.*;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.consensus.script.ConsensusScriptFlags;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;
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
            long lockTimeCutoff,
            long previousMedianTimePast,
            UtxoStore utxoStore,
            NetworkParameters networkParameters,
            AncestorMedianTimePastResolver medianTimePastResolver
    ) {
        if (utxoStore == null) {
            throw new IllegalArgumentException(
                    "utxoStore must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
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
                        lockTimeCutoff,
                        previousMedianTimePast,
                        overlay,
                        networkParameters,
                        medianTimePastResolver
                );

        return new BlockConnectChanges(
                overlay.changes(),
                undoData
        );
    }

    public static BlockUndoData apply(
            Block block,
            long blockHeight,
            long lockTimeCutoff,
            long previousMedianTimePast,
            UtxoOverlay overlay,
            NetworkParameters networkParameters,
            AncestorMedianTimePastResolver medianTimePastResolver
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }
        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }

        if (medianTimePastResolver == null) {
            throw new IllegalArgumentException(
                    "medianTimePastResolver must not be null"
            );
        }
        if (lockTimeCutoff < 0) {
            throw new IllegalArgumentException(
                    "lockTimeCutoff must not be negative"
            );
        }
        if (overlay == null) {
            throw new IllegalArgumentException(
                    "overlay must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        /*
         * Нельзя начинать contextual/UTXO validation,
         * пока базовая структура блока не подтверждена.
         *
         * Здесь проверяются в том числе:
         * - наличие транзакций;
         * - tx[0] является coinbase;
         * - других coinbase нет;
         * - basic transaction rules;
         * - block weight;
         * - Merkle root;
         * - mutated Merkle tree.
         */
        BlockValidator.validateStructure(
                block
        );

        List<Transaction> transactions =
                block.transactions();

        Bip34Validator.validate(
                transactions.get(0),
                blockHeight,
                networkParameters
        );

        UtxoOverlayView utxoView =
                new UtxoOverlayView(
                        overlay
                );

        List<TransactionUndo> transactionUndos =
                new ArrayList<>();

        long totalFees = 0L;

        boolean enforceBip30 =
                Bip30.shouldEnforce(
                        blockHeight,
                        block.header().hash(),
                        networkParameters
                );

        int scriptVerifyFlags =
                ConsensusScriptFlags.forBlock(
                        blockHeight,
                        block.header().hash(),
                        networkParameters
                );

        WitnessCommitmentValidator.validate(block, blockHeight >= networkParameters.segwitHeight());
        SignetBlockValidator.validate(block, networkParameters);
        long sigOpsCost = 0;

        for (int transactionIndex = 0;
             transactionIndex < transactions.size();
             transactionIndex++) {

            Transaction transaction =
                    transactions.get(
                            transactionIndex
                    );
            /*
             * BIP30 проверяется ДО изменения UTXO
             * текущей транзакцией.
             */
            if (enforceBip30) {
                validateBip30(
                        transaction,
                        overlay
                );
            }
            boolean coinbase =
                    transactionIndex == 0;

            TransactionFinality.validate(
                        transaction,
                        blockHeight,
                        lockTimeCutoff
                );

            sigOpsCost = Math.addExact(sigOpsCost,
                    TransactionSigOpCost.calculate(transaction, utxoView, scriptVerifyFlags));
            BlockSigOpsValidator.validate(sigOpsCost);

            if (!coinbase) {

                TransactionContextResult contextResult =
                        ContextualTransactionValidator.validateInputs(
                                transaction,
                                blockHeight,
                                utxoView
                        );

                /*
                 * ContextualTransactionValidator уже подтвердил,
                 * что все referenced UTXO существуют.
                 *
                 * Поэтому теперь безопасно формируем BIP68 context.
                 */
                if (blockHeight >= networkParameters.csvHeight()
                        && Integer.toUnsignedLong(transaction.version()) >= 2) {

                    List<InputConfirmation> inputConfirmations =
                            buildInputConfirmations(
                                    transaction,
                                    overlay,
                                    medianTimePastResolver
                            );

                    SequenceLockValidator.validate(
                            transaction,
                            inputConfirmations,
                            blockHeight,
                            previousMedianTimePast,
                            networkParameters
                    );
                }

                /*
                 * Все referenced UTXO существуют, contextual
                 * value/maturity validation уже выполнена,
                 * BIP68 также подтверждён.
                 *
                 * Overlay пока НЕ мутирован.
                 *
                 * Поэтому каждый input теперь должен доказать
                 * право потратить соответствующий UTXO.
                 */
                InputScriptValidator.validateAll(
                        transaction,
                        utxoView,
                        scriptVerifyFlags
                );

                try {
                    totalFees =
                            Math.addExact(
                                    totalFees,
                                    contextResult.fee()
                            );
                } catch (ArithmeticException e) {
                    throw new BlockValidationException(
                            "Block transaction fees overflow"
                    );
                }
                if (!Money.isValidAmount(totalFees)) {
                    throw new BlockValidationException(
                            "Accumulated block fees out of range"
                    );
                }

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
        CoinbaseValidator.validateReward(
                transactions.get(0),
                blockHeight,
                totalFees,
                networkParameters
        );

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

            byte[] script = output.scriptPubKey();
            if (script.length > 10_000 || (script.length > 0 && script[0] == 0x6a)) continue;

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

    private static void validateBip30(
            Transaction transaction,
            UtxoOverlay overlay
    ) {
        List<TxOut> outputs =
                transaction.outputs();

        for (int outputIndex = 0;
             outputIndex < outputs.size();
             outputIndex++) {

            OutPoint outPoint =
                    new OutPoint(
                            transaction.txId(),
                            new UInt32(outputIndex)
                    );

            if (overlay.find(outPoint).isPresent()) {
                throw new BlockValidationException(
                        "BIP30 violation: transaction would overwrite "
                                + "an existing unspent output: "
                                + transaction.txId().toDisplayHex()
                                + ":"
                                + outputIndex
                );
            }
        }
    }
    private static List<InputConfirmation> buildInputConfirmations(
            Transaction transaction,
            UtxoOverlay overlay,
            AncestorMedianTimePastResolver medianTimePastResolver
    ) {
        List<InputConfirmation> result =
                new ArrayList<>(
                        transaction.inputs().size()
                );

        for (TxIn input : transaction.inputs()) {

            StoredUtxo utxo =
                    overlay.find(
                            input.previousOutput()
                    ).orElseThrow(
                            () -> new IllegalStateException(
                                    "UTXO disappeared after contextual "
                                            + "validation: "
                                            + input.previousOutput()
                            )
                    );

            long sequence =
                    input.sequence().value();

            long previousMedianTimePast = 0L;

            /*
             * Bitcoin Core обращается к MTP предка
             * только для активного time-based
             * relative sequence lock.
             *
             * DISABLE_FLAG:
             * input не участвует в BIP68.
             *
             * TYPE_FLAG == 0:
             * height-based lock, MTP не требуется.
             */
            boolean sequenceLockEnabled =
                    (sequence
                            & SequenceLocks
                            .SEQUENCE_LOCKTIME_DISABLE_FLAG) == 0;

            boolean timeBased =
                    (sequence
                            & SequenceLocks
                            .SEQUENCE_LOCKTIME_TYPE_FLAG) != 0;

            if (sequenceLockEnabled
                    && timeBased) {

                previousMedianTimePast =
                        medianTimePastResolver
                                .resolveForCoinHeight(
                                        utxo.height()
                                );
            }

            result.add(
                    new InputConfirmation(
                            utxo.height(),
                            previousMedianTimePast
                    )
            );
        }

        return List.copyOf(result);
    }
}
