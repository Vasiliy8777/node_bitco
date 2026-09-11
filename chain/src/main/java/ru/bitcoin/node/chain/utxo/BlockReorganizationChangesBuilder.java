package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BlockReorganizationChangesBuilder {

    private BlockReorganizationChangesBuilder() {
    }

    public static BlockReorganizationChanges build(
            List<BlockToDisconnect> disconnectBlocks,
            List<BlockToConnect> connectBlocks,
            UtxoStore utxoStore
    ) {
        if (disconnectBlocks == null) {
            throw new IllegalArgumentException(
                    "disconnectBlocks must not be null"
            );
        }

        if (connectBlocks == null) {
            throw new IllegalArgumentException(
                    "connectBlocks must not be null"
            );
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

            BlockUndoData undoData =
                    BlockConnectChangesBuilder.apply(
                            blockToConnect.block(),
                            blockToConnect.height(),
                            overlay
                    );

            Hash256 blockHash =
                    blockToConnect
                            .block()
                            .header()
                            .hash();

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
}