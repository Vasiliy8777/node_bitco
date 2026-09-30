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
import java.util.logging.Logger;

public final class ChainReorganizationExecutor {

    private static final Logger LOG = Logger.getLogger(ChainReorganizationExecutor.class.getName());

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

    public void execute(ChainUpdate update) {
        PreparedChainReorganization prepared = prepare(update);
        commit(prepared);
    }

    public PreparedChainReorganization prepare(ChainUpdate update, Block suppliedConnectBlock) {
        return prepare(update, suppliedConnectBlock, invalidBlockObserver);
    }

    public PreparedChainReorganization prepare(
            ChainUpdate update,
            Block suppliedConnectBlock,
            InvalidBlockObserver preparationInvalidBlockObserver
    ) {
        if (update == null) throw new IllegalArgumentException("update must not be null");
        if (suppliedConnectBlock == null) return prepare(update);
        if (preparationInvalidBlockObserver == null) {
            throw new IllegalArgumentException("preparationInvalidBlockObserver must not be null");
        }
        ReorganizationPlan plan = update.reorganizationPlan();
        diagnostic(plan, "load disconnect start");
        List<BlockToDisconnect> disconnectBlocks = loadDisconnectBlocks(plan.blocksToDisconnect());
        diagnostic(plan, "load disconnect done count=" + disconnectBlocks.size());
        List<BlockToConnect> connectBlocks = loadConnectBlocks(plan.blocksToConnect(), suppliedConnectBlock);
        diagnostic(plan, "load connect done count=" + connectBlocks.size());
        BlockIndexLookup preparationLookup = preparationLookup(plan);
        diagnostic(plan, "preparation lookup done");
        BlockReorganizationChanges changes = BlockReorganizationChangesBuilder.build(
                disconnectBlocks, connectBlocks, utxoStore, networkParameters,
                preparationLookup, preparationInvalidBlockObserver, assumeValidPolicy);
        diagnostic(plan, "changes build done");
        return new PreparedChainReorganization(update, changes);
    }

    /** Fully loads and validates a reorganization without mutating persistent chain state. */
    public PreparedChainReorganization prepare(ChainUpdate update) {
        if (update == null) throw new IllegalArgumentException("update must not be null");
        ReorganizationPlan plan = update.reorganizationPlan();
        List<BlockToDisconnect> disconnectBlocks = loadDisconnectBlocks(plan.blocksToDisconnect());
        List<BlockToConnect> connectBlocks = loadConnectBlocks(plan.blocksToConnect());
        BlockIndexLookup preparationLookup = preparationLookup(plan);
        BlockReorganizationChanges changes = BlockReorganizationChangesBuilder.build(
                disconnectBlocks, connectBlocks, utxoStore, networkParameters,
                preparationLookup, invalidBlockObserver, assumeValidPolicy);
        return new PreparedChainReorganization(update, changes);
    }


    private static void diagnostic(ReorganizationPlan plan, String stage) {
        // Per-block IBD diagnostic logging disabled for throughput.
    }

    public void commit(PreparedChainReorganization prepared) {
        if (prepared == null) throw new IllegalArgumentException("prepared must not be null");
        transitionManager.commit(prepared.update(), prepared.changes());
    }

    public void commit(PreparedChainReorganization prepared,
                       java.util.function.Consumer<ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch> extraWrites) {
        if (prepared == null) throw new IllegalArgumentException("prepared must not be null");
        transitionManager.commit(prepared.update(), prepared.changes(), extraWrites);
    }

    /**
     * Creates a read-only lookup for the prepare phase.  A freshly received block index
     * deliberately is not durable until ChainTransitionManager commits the whole transition,
     * but contextual validation still needs to resolve that index and its in-plan ancestors.
     */
    private BlockIndexLookup preparationLookup(ReorganizationPlan plan) {
        java.util.Map<ru.bitcoin.node.common.types.Hash256, BlockIndex> overlay =
                new java.util.HashMap<>(Math.max(16, plan.blocksToConnect().size() * 2));
        for (BlockIndex index : plan.blocksToConnect()) {
            if (index == null) {
                throw new IllegalStateException("Connect BlockIndex must not be null");
            }
            overlay.put(index.hash(), index);
        }

        return new BlockIndexAncestorLookup() {
            @Override
            public BlockIndex find(ru.bitcoin.node.common.types.Hash256 hash) {
                BlockIndex local = overlay.get(hash);
                return local != null ? local : blockIndexLookup.find(hash);
            }

            @Override
            public BlockIndex ancestor(BlockIndex index, long targetHeight) {
                if (index == null) throw new IllegalArgumentException("index must not be null");
                if (targetHeight < 0 || targetHeight > index.height()) {
                    throw new IllegalArgumentException("Invalid ancestor height: " + targetHeight);
                }

                BlockIndex current = index;
                while (current.height() > targetHeight && overlay.containsKey(current.hash())) {
                    current = find(current.previousBlockHash());
                    if (current == null) {
                        throw new IllegalStateException("Missing prepare-phase ancestor");
                    }
                }
                if (current.height() == targetHeight) return current;

                if (blockIndexLookup instanceof BlockIndexAncestorLookup ancestorLookup) {
                    return ancestorLookup.ancestor(current, targetHeight);
                }

                while (current.height() > targetHeight) {
                    current = find(current.previousBlockHash());
                    if (current == null) {
                        throw new IllegalStateException("Missing ancestor while preparing reorganization");
                    }
                }
                return current;
            }
        };
    }


    public void commitWithStagedNewTipIndex(
            PreparedChainReorganization prepared,
            java.util.function.Consumer<ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch> extraWrites
    ) {
        if (prepared == null) throw new IllegalArgumentException("prepared must not be null");
        if (extraWrites == null) throw new IllegalArgumentException("extraWrites must not be null");
        transitionManager.commitWithStagedNewTipIndex(
                prepared.update(), prepared.changes(), extraWrites);
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
        return loadConnectBlocks(indexes, null);
    }

    private List<BlockToConnect> loadConnectBlocks(
            List<BlockIndex> indexes,
            Block suppliedConnectBlock
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

            Block block;
            if (suppliedConnectBlock != null && suppliedConnectBlock.hash().equals(index.hash())) {
                block = suppliedConnectBlock;
            } else {
                block = blockStore
                        .find(index.hash())
                        .orElseThrow(() -> new IllegalStateException(
                                "Block body not found for connect: " + index.hash().toDisplayHex()));
            }

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