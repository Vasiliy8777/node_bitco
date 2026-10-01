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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.concurrent.atomic.LongAdder;

public final class BlockConnectChangesBuilder {

    private static final Logger LOG = Logger.getLogger(BlockConnectChangesBuilder.class.getName());

    // Stage 21 IBD diagnostics. These counters are observational only and do not
    // participate in consensus decisions. LongAdder keeps snapshot reads cheap.
    private static final LongAdder diagnosticBlocks = new LongAdder();
    private static final LongAdder diagnosticStructureNanos = new LongAdder();
    private static final LongAdder diagnosticSetupNanos = new LongAdder();
    private static final LongAdder diagnosticBip30Nanos = new LongAdder();
    private static final LongAdder diagnosticFinalitySigOpsNanos = new LongAdder();
    private static final LongAdder diagnosticContextInputsNanos = new LongAdder();
    private static final LongAdder diagnosticSequenceLocksNanos = new LongAdder();
    private static final LongAdder diagnosticScriptsNanos = new LongAdder();
    private static final LongAdder diagnosticSpendInputsNanos = new LongAdder();
    private static final LongAdder diagnosticCreateOutputsNanos = new LongAdder();
    private static final LongAdder diagnosticRewardNanos = new LongAdder();

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
                        medianTimePastResolver,
                        true
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
        return apply(block, blockHeight, lockTimeCutoff, previousMedianTimePast, overlay,
                networkParameters, medianTimePastResolver, true);
    }

    public static BlockUndoData apply(
            Block block,
            long blockHeight,
            long lockTimeCutoff,
            long previousMedianTimePast,
            UtxoOverlay overlay,
            NetworkParameters networkParameters,
            AncestorMedianTimePastResolver medianTimePastResolver,
            boolean verifyScripts
    ) {
        return apply(block, blockHeight, lockTimeCutoff, previousMedianTimePast, overlay,
                networkParameters, medianTimePastResolver, verifyScripts,
                Bip30.shouldEnforce(blockHeight, block.header().hash(), networkParameters));
    }

    public static BlockUndoData apply(
            Block block,
            long blockHeight,
            long lockTimeCutoff,
            long previousMedianTimePast,
            UtxoOverlay overlay,
            NetworkParameters networkParameters,
            AncestorMedianTimePastResolver medianTimePastResolver,
            boolean verifyScripts,
            boolean enforceBip30
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
        diagnosticBlocks.increment();
        long phaseStarted = System.nanoTime();
        diagnostic(blockHeight, block, "structure start");
        BlockValidator.validateStructure(block);
        diagnosticStructureNanos.add(System.nanoTime() - phaseStarted);
        diagnostic(blockHeight, block, "structure done");

        List<Transaction> transactions =
                block.transactions();

        phaseStarted = System.nanoTime();
        diagnostic(blockHeight, block, "BIP34 start");
        Bip34Validator.validate(transactions.get(0), blockHeight, networkParameters);
        diagnostic(blockHeight, block, "BIP34 done");

        UtxoOverlayView utxoView =
                new UtxoOverlayView(
                        overlay
                );

        List<TransactionUndo> transactionUndos =
                new ArrayList<>();

        long totalFees = 0L;


        diagnostic(blockHeight, block, "script flags start");
        int scriptVerifyFlags = ConsensusScriptFlags.forBlock(
                blockHeight, block.header().hash(), networkParameters);
        diagnostic(blockHeight, block, "script flags done flags=" + scriptVerifyFlags);

        diagnostic(blockHeight, block, "witness validation start");
        WitnessCommitmentValidator.validate(block, blockHeight >= networkParameters.segwitHeight());
        diagnostic(blockHeight, block, "witness validation done");
        SignetBlockValidator.validate(block, networkParameters);
        diagnostic(blockHeight, block, "signet validation done");
        diagnosticSetupNanos.add(System.nanoTime() - phaseStarted);

        // Stage 25: resolve the persistent portion of all block inputs in one
        // storage batch before the transaction loop. The overlay still applies
        // transactions strictly in block order, so same-block spends and all
        // consensus/state transitions retain their original semantics.
        prefetchBlockInputs(transactions, overlay);

        long sigOpsCost = 0;

        diagnostic(blockHeight, block, "transactions start count=" + transactions.size());
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
                phaseStarted = System.nanoTime();
                validateBip30(
                        transaction,
                        overlay
                );
                diagnosticBip30Nanos.add(System.nanoTime() - phaseStarted);
            }
            boolean coinbase =
                    transactionIndex == 0;

            phaseStarted = System.nanoTime();
            TransactionFinality.validate(
                    transaction,
                    blockHeight,
                    lockTimeCutoff
            );

            sigOpsCost = Math.addExact(sigOpsCost,
                    TransactionSigOpCost.calculate(transaction, utxoView, scriptVerifyFlags));
            BlockSigOpsValidator.validate(sigOpsCost);
            diagnosticFinalitySigOpsNanos.add(System.nanoTime() - phaseStarted);

            if (!coinbase) {

                phaseStarted = System.nanoTime();
                TransactionContextResult contextResult =
                        ContextualTransactionValidator.validateInputs(
                                transaction,
                                blockHeight,
                                utxoView
                        );
                diagnosticContextInputsNanos.add(System.nanoTime() - phaseStarted);

                /*
                 * ContextualTransactionValidator уже подтвердил,
                 * что все referenced UTXO существуют.
                 *
                 * Поэтому теперь безопасно формируем BIP68 context.
                 */
                if (blockHeight >= networkParameters.csvHeight()
                        && Integer.toUnsignedLong(transaction.version()) >= 2) {

                    phaseStarted = System.nanoTime();
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
                    diagnosticSequenceLocksNanos.add(System.nanoTime() - phaseStarted);
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
                if (verifyScripts) {
                    phaseStarted = System.nanoTime();
                    InputScriptValidator.validateAll(
                            transaction,
                            utxoView,
                            scriptVerifyFlags
                    );
                    diagnosticScriptsNanos.add(System.nanoTime() - phaseStarted);
                }

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

                phaseStarted = System.nanoTime();
                TransactionUndo transactionUndo =
                        spendInputs(
                                transaction,
                                overlay
                        );
                diagnosticSpendInputsNanos.add(System.nanoTime() - phaseStarted);

                transactionUndos.add(
                        transactionUndo
                );
            }

            phaseStarted = System.nanoTime();
            createOutputs(
                    transaction,
                    blockHeight,
                    coinbase,
                    overlay,
                    enforceBip30
            );
            diagnosticCreateOutputsNanos.add(System.nanoTime() - phaseStarted);
        }
        diagnostic(blockHeight, block, "transactions done");
        diagnostic(blockHeight, block, "coinbase reward start");
        phaseStarted = System.nanoTime();
        CoinbaseValidator.validateReward(transactions.get(0), blockHeight, totalFees, networkParameters);
        diagnosticRewardNanos.add(System.nanoTime() - phaseStarted);
        diagnostic(blockHeight, block, "coinbase reward done");

        return new BlockUndoData(
                transactionUndos
        );
    }

    public static DiagnosticSnapshot diagnosticSnapshot() {
        return new DiagnosticSnapshot(
                diagnosticBlocks.sum(),
                diagnosticStructureNanos.sum(),
                diagnosticSetupNanos.sum(),
                diagnosticBip30Nanos.sum(),
                diagnosticFinalitySigOpsNanos.sum(),
                diagnosticContextInputsNanos.sum(),
                diagnosticSequenceLocksNanos.sum(),
                diagnosticScriptsNanos.sum(),
                diagnosticSpendInputsNanos.sum(),
                diagnosticCreateOutputsNanos.sum(),
                diagnosticRewardNanos.sum()
        );
    }

    public record DiagnosticSnapshot(
            long blocks,
            long structureNanos,
            long setupNanos,
            long bip30Nanos,
            long finalitySigOpsNanos,
            long contextInputsNanos,
            long sequenceLocksNanos,
            long scriptsNanos,
            long spendInputsNanos,
            long createOutputsNanos,
            long rewardNanos
    ) {
        public DiagnosticSnapshot minus(DiagnosticSnapshot baseline) {
            if (baseline == null) throw new IllegalArgumentException("baseline must not be null");
            return new DiagnosticSnapshot(
                    blocks - baseline.blocks,
                    structureNanos - baseline.structureNanos,
                    setupNanos - baseline.setupNanos,
                    bip30Nanos - baseline.bip30Nanos,
                    finalitySigOpsNanos - baseline.finalitySigOpsNanos,
                    contextInputsNanos - baseline.contextInputsNanos,
                    sequenceLocksNanos - baseline.sequenceLocksNanos,
                    scriptsNanos - baseline.scriptsNanos,
                    spendInputsNanos - baseline.spendInputsNanos,
                    createOutputsNanos - baseline.createOutputsNanos,
                    rewardNanos - baseline.rewardNanos
            );
        }
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
            UtxoOverlay overlay,
            boolean enforceBip30
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
            if (ru.bitcoin.node.script.UnspendableScript.isUnspendable(script)) continue;

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

            if (enforceBip30) {
                // validateBip30() already populated the overlay with the actual
                // persistent pre-state, so normal put() reuses that snapshot.
                overlay.put(
                        outPoint,
                        utxo
                );
            } else {
                // Core-style BIP34/BIP30 fast path: the consensus layer has
                // established that no persistent overwrite check is required.
                // Do not turn every newly-created output into a negative RocksDB
                // lookup merely to build the UTXO delta.
                overlay.putKnownAbsent(
                        outPoint,
                        utxo
                );
            }
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
    private static void prefetchBlockInputs(
            List<Transaction> transactions,
            UtxoOverlay overlay
    ) {
        Set<OutPoint> inputs = new LinkedHashSet<>();
        for (int transactionIndex = 1; transactionIndex < transactions.size(); transactionIndex++) {
            for (TxIn input : transactions.get(transactionIndex).inputs()) {
                inputs.add(input.previousOutput());
            }
        }
        overlay.prefetch(inputs);
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
    private static void diagnostic(long height, Block block, String stage) {
        // Per-block IBD diagnostic logging disabled for throughput.
    }


}
