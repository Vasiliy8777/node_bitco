package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import ru.bitcoin.node.chain.utxo.BlockReorganizationChangesBuilder;
import ru.bitcoin.node.chain.utxo.BlockToConnect;
import ru.bitcoin.node.chain.utxo.BlockToDisconnect;
import ru.bitcoin.node.consensus.transaction.LockTimeCutoff;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.BlockStore;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.UndoStore;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.ArrayList;
import java.util.List;

public final class ChainReorganizationExecutor {

    private final BlockIndexLookup blockIndexLookup;
    private final BlockStore blockStore;
    private final UndoStore undoStore;
    private final UtxoStore utxoStore;
    private final ChainTransitionManager transitionManager;
    private final NetworkParameters networkParameters;
    private final InvalidBlockObserver invalidBlockObserver;
    private final AssumeValidPolicy assumeValidPolicy;

    public ChainReorganizationExecutor(
            BlockStore blockStore,
            UndoStore undoStore,
            UtxoStore utxoStore,
            ChainTransitionManager transitionManager,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup
    ) {
        this(
                blockStore,
                undoStore,
                utxoStore,
                transitionManager,
                networkParameters,
                blockIndexLookup,
                InvalidBlockObserver.noop(),
                AssumeValidPolicy.verifyAll(blockIndexLookup, networkParameters)
        );
    }

    public ChainReorganizationExecutor(
            BlockStore blockStore,
            UndoStore undoStore,
            UtxoStore utxoStore,
            ChainTransitionManager transitionManager,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup,
            InvalidBlockObserver invalidBlockObserver
    ) {
        this(blockStore, undoStore, utxoStore, transitionManager, networkParameters, blockIndexLookup,
                invalidBlockObserver, AssumeValidPolicy.verifyAll(blockIndexLookup, networkParameters));
    }

    public ChainReorganizationExecutor(
            BlockStore blockStore,
            UndoStore undoStore,
            UtxoStore utxoStore,
            ChainTransitionManager transitionManager,
            NetworkParameters networkParameters,
            BlockIndexLookup blockIndexLookup,
            InvalidBlockObserver invalidBlockObserver,
            AssumeValidPolicy assumeValidPolicy
    ) {
        if (blockStore == null) {
            throw new IllegalArgumentException(
                    "blockStore must not be null"
            );
        }
        if (blockIndexLookup == null) {
            throw new IllegalArgumentException(
                    "blockIndexLookup must not be null"
            );
        }
        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }
        if (undoStore == null) {
            throw new IllegalArgumentException(
                    "undoStore must not be null"
            );
        }

        if (utxoStore == null) {
            throw new IllegalArgumentException(
                    "utxoStore must not be null"
            );
        }

        if (transitionManager == null) {
            throw new IllegalArgumentException(
                    "transitionManager must not be null"
            );
        }
        this.blockIndexLookup = blockIndexLookup;
        this.blockStore = blockStore;
        this.undoStore = undoStore;
        this.utxoStore = utxoStore;
        this.transitionManager = transitionManager;
        this.networkParameters = networkParameters;
        this.invalidBlockObserver =
                java.util.Objects.requireNonNull(
                        invalidBlockObserver,
                        "invalidBlockObserver"
                );
        this.assumeValidPolicy = java.util.Objects.requireNonNull(assumeValidPolicy, "assumeValidPolicy");
    }

    public void execute(
            ChainUpdate update
    ) {
        if (update == null) {
            throw new IllegalArgumentException(
                    "update must not be null"
            );
        }

        ReorganizationPlan plan =
                update.reorganizationPlan();

        /*
         * Сначала полностью загружаем всё необходимое
         * для отключаемой старой ветки.
         */
        List<BlockToDisconnect> disconnectBlocks =
                loadDisconnectBlocks(
                        plan.blocksToDisconnect()
                );

        /*
         * Затем полностью загружаем новую ветку.
         *
         * Если body хотя бы одного блока отсутствует,
         * мы упадём здесь ДО любых изменений UTXO,
         * active tip или RAM ChainState.
         */
        List<BlockToConnect> connectBlocks =
                loadConnectBlocks(
                        plan.blocksToConnect()
                );

        /*
         * Строим полное изменение UTXO в памяти.
         */
        BlockReorganizationChanges changes =
                BlockReorganizationChangesBuilder.build(
                        disconnectBlocks,
                        connectBlocks,
                        utxoStore,
                        networkParameters,
                        blockIndexLookup,
                        invalidBlockObserver,
                        assumeValidPolicy
                );

        /*
         * Только после успешной подготовки
         * выполняем persistent transition.
         *
         * ChainTransitionManager:
         *
         * 1. проверяет RAM oldTip
         * 2. делает atomic RocksDB commit
         * 3. меняет RAM ChainState
         */
        transitionManager.commit(
                update,
                changes
        );
    }

    private List<BlockToDisconnect> loadDisconnectBlocks(
            List<BlockIndex> indexes
    ) {
        List<BlockToDisconnect> result =
                new ArrayList<>(
                        indexes.size()
                );

        for (BlockIndex index : indexes) {

            if (index == null) {
                throw new IllegalStateException(
                        "Disconnect BlockIndex must not be null"
                );
            }

            Block block =
                    blockStore
                            .find(index.hash())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Block body not found for disconnect: "
                                                            + index.hash()
                                                            .toDisplayHex()
                                            )
                            );

            /*
             * Для отключения блока Undo обязателен.
             */
            BlockUndoData undoData =
                    undoStore
                            .find(index.hash())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Undo data not found for disconnect: "
                                                            + index.hash()
                                                            .toDisplayHex()
                                            )
                            );

            verifyBlockMatchesIndex(
                    block,
                    index
            );

            result.add(
                    new BlockToDisconnect(
                            block,
                            undoData
                    )
            );
        }

        return List.copyOf(result);
    }

    private List<BlockToConnect> loadConnectBlocks(
            List<BlockIndex> indexes
    ) {
        List<BlockToConnect> result =
                new ArrayList<>(
                        indexes.size()
                );

        for (BlockIndex index : indexes) {

            if (index == null) {
                throw new IllegalStateException(
                        "Connect BlockIndex must not be null"
                );
            }

            Block block =
                    blockStore
                            .find(index.hash())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Block body not found for connect: "
                                                            + index.hash()
                                                            .toDisplayHex()
                                            )
                            );

            verifyBlockMatchesIndex(
                    block,
                    index
            );
            long blockTimestamp =
                    block.header()
                            .timestamp()
                            .value();

            long previousMedianTimePast =
                    0L;

            if (index.height()
                    >= networkParameters.csvHeight()) {

                if (index.height() == 0) {
                    throw new IllegalStateException(
                            "BIP113 cannot require previous MTP for genesis block"
                    );
                }

                BlockIndex parent =
                        blockIndexLookup.find(
                                index.previousBlockHash()
                        );

                if (parent == null) {
                    throw new IllegalStateException(
                            "Parent BlockIndex not found while calculating "
                                    + "lock-time cutoff for block: "
                                    + index.hash().toDisplayHex()
                    );
                }

                previousMedianTimePast =
                        MedianTimePast.calculate(
                                parent,
                                blockIndexLookup
                        );
            }

            long lockTimeCutoff =
                    LockTimeCutoff.calculate(
                            index.height(),
                            blockTimestamp,
                            previousMedianTimePast,
                            networkParameters
                    );

            result.add(
                    new BlockToConnect(
                            block,
                            index.height(),
                            lockTimeCutoff,
                            previousMedianTimePast
                    )
            );
        }
        return List.copyOf(result);
    }

    private static void verifyBlockMatchesIndex(
            Block block,
            BlockIndex index
    ) {
        if (!block.header()
                .hash()
                .equals(index.hash())) {

            throw new IllegalStateException(
                    "Stored block does not match BlockIndex. "
                            + "Expected: "
                            + index.hash().toDisplayHex()
                            + ", actual: "
                            + block.header()
                            .hash()
                            .toDisplayHex()
            );
        }
    }
}