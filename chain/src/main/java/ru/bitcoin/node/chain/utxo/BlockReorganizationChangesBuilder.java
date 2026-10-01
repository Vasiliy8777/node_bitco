package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.chain.AncestorMedianTimePastResolver;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.chain.BlockIndexAncestorLookup;
import ru.bitcoin.node.chain.InvalidBlockObserver;
import ru.bitcoin.node.chain.AssumeValidPolicy;
import ru.bitcoin.node.consensus.block.BlockValidationException;
import ru.bitcoin.node.consensus.block.Bip30;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.script.ScriptExecutionException;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class BlockReorganizationChangesBuilder {

    private static final Logger LOG = Logger.getLogger(BlockReorganizationChangesBuilder.class.getName());

    private BlockReorganizationChangesBuilder() {
    }

    public static BlockReorganizationChanges build(
            List<BlockToDisconnect> disconnectBlocks,
            List<BlockToConnect> connectBlocks,
            UtxoStore utxoStore,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup
    ) {
        return build(
                disconnectBlocks,
                connectBlocks,
                utxoStore,
                networkParameters,
                blockIndexLookup,
                InvalidBlockObserver.noop(),
                AssumeValidPolicy.verifyAll(blockIndexLookup, networkParameters)
        );
    }

    public static BlockReorganizationChanges build(
            List<BlockToDisconnect> disconnectBlocks,
            List<BlockToConnect> connectBlocks,
            UtxoStore utxoStore,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup,
            InvalidBlockObserver invalidBlockObserver
    ) {
        return build(disconnectBlocks, connectBlocks, utxoStore, networkParameters, blockIndexLookup,
                invalidBlockObserver, AssumeValidPolicy.verifyAll(blockIndexLookup, networkParameters));
    }

    public static BlockReorganizationChanges build(
            List<BlockToDisconnect> disconnectBlocks,
            List<BlockToConnect> connectBlocks,
            UtxoStore utxoStore,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup,
            InvalidBlockObserver invalidBlockObserver,
            AssumeValidPolicy assumeValidPolicy
    ) {
        if (disconnectBlocks == null) {
            throw new IllegalArgumentException(
                    "disconnectBlocks must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }
        if (blockIndexLookup == null) {
            throw new IllegalArgumentException(
                    "blockIndexLookup must not be null"
            );
        }
        if (connectBlocks == null) {
            throw new IllegalArgumentException(
                    "connectBlocks must not be null"
            );
        }

        if (invalidBlockObserver == null) {
            throw new IllegalArgumentException(
                    "invalidBlockObserver must not be null"
            );
        }

        if (assumeValidPolicy == null) {
            throw new IllegalArgumentException("assumeValidPolicy must not be null");
        }

        if (utxoStore == null) {
            throw new IllegalArgumentException(
                    "utxoStore must not be null"
            );
        }

        if (disconnectBlocks.stream().anyMatch(
                block -> block == null
        )) {
            throw new IllegalArgumentException(
                    "disconnectBlocks must not contain null"
            );
        }

        if (connectBlocks.stream().anyMatch(
                block -> block == null
        )) {
            throw new IllegalArgumentException(
                    "connectBlocks must not contain null"
            );
        }

        UtxoOverlay overlay =
                new UtxoOverlay(
                        utxoStore
                );

        /*
         * Порядок disconnectBlocks обязан быть:
         *
         * current tip
         *      ↓
         * common ancestor
         */
        for (BlockToDisconnect blockToDisconnect
                : disconnectBlocks) {

            BlockDisconnectApplier.apply(
                    blockToDisconnect.block(),
                    blockToDisconnect.undoData(),
                    overlay
            );
        }

        Map<Hash256, BlockUndoData> connectedUndo =
                new LinkedHashMap<>();

        /*
         * Порядок connectBlocks обязан быть:
         *
         * common ancestor child
         *      ↓
         * new tip
         */
        for (BlockToConnect blockToConnect
                : connectBlocks) {

            Hash256 blockHash =
                    blockToConnect
                            .block()
                            .header()
                            .hash();

            BlockIndex candidateIndex =
                    blockIndexLookup.find(
                            blockHash
                    );

            if (candidateIndex == null) {
                throw new IllegalStateException(
                        "BlockIndex not found for candidate block: "
                                + blockHash.toDisplayHex()
                );
            }

            if (candidateIndex.height()
                    != blockToConnect.height()) {

                throw new IllegalStateException(
                        "Candidate BlockIndex height mismatch. "
                                + "Block: "
                                + blockHash.toDisplayHex()
                                + ", expected height: "
                                + blockToConnect.height()
                                + ", actual height: "
                                + candidateIndex.height()
                );
            }

            diagnostic(candidateIndex, "candidate lookup done");
            AncestorMedianTimePastResolver medianTimePastResolver =
                    new AncestorMedianTimePastResolver(
                            candidateIndex,
                            blockIndexLookup
                    );
            diagnostic(candidateIndex, "MTP resolver created");

            final BlockUndoData undoData;

            try {
                diagnostic(candidateIndex, "assumevalid start");
                boolean verifyScripts = assumeValidPolicy.shouldVerifyScripts(candidateIndex);
                diagnostic(candidateIndex, "assumevalid done verifyScripts=" + verifyScripts);
                diagnostic(candidateIndex, "connect apply start");
                boolean enforceBip30 = Bip30.shouldEnforce(
                        blockToConnect.height(),
                        blockHash,
                        networkParameters,
                        isOnKnownBip34Chain(candidateIndex, blockIndexLookup, networkParameters)
                );
                undoData =
                        BlockConnectChangesBuilder.apply(
                                blockToConnect.block(),
                                blockToConnect.height(),
                                blockToConnect.lockTimeCutoff(),
                                blockToConnect.previousMedianTimePast(),
                                overlay,
                                networkParameters,
                                medianTimePastResolver,
                                verifyScripts,
                                enforceBip30
                        );
                diagnostic(candidateIndex, "connect apply done");
            } catch (BlockValidationException
                     | TransactionValidationException
                     | ScriptExecutionException exception) {
                invalidBlockObserver.onInvalidBlock(candidateIndex);
                throw exception;
            }

            if (connectedUndo.put(
                    blockHash,
                    undoData
            ) != null) {

                throw new IllegalStateException(
                        "Duplicate block in connect sequence: "
                                + blockHash.toDisplayHex()
                );
            }
        }

        return new BlockReorganizationChanges(
                overlay.changes(),
                connectedUndo
        );
    }

    private static boolean isOnKnownBip34Chain(
            BlockIndex candidateIndex,
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        Hash256 expected = Bip30.knownBip34ActivationHash(parameters);
        if (expected == null || candidateIndex.height() < parameters.bip34Height()) return false;

        try {
            BlockIndex activation;
            if (lookup instanceof BlockIndexAncestorLookup ancestorLookup) {
                activation = ancestorLookup.ancestor(candidateIndex, parameters.bip34Height());
            } else {
                activation = candidateIndex;
                while (activation.height() > parameters.bip34Height()) {
                    activation = lookup.find(activation.previousBlockHash());
                    if (activation == null) return false;
                }
            }
            return activation.height() == parameters.bip34Height()
                    && activation.hash().equals(expected);
        } catch (IllegalStateException incompleteIndex) {
            // Fail safe: incomplete ancestry means explicit BIP30 checking stays enabled.
            return false;
        }
    }
    private static void diagnostic(BlockIndex index, String stage) {
        // Per-block IBD diagnostic logging disabled for throughput.
    }


}