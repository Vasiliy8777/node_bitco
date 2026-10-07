package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.util.Objects;

public final class NodeSyncInfrastructure {

    private final BlockIndexLookup blockIndexLookup;
    private final RocksDbBlockStore blockStore;
    private final HeaderChainState headerChainState;
    private final HeaderSyncService headerSyncService;
    private final BlockLocatorBuilder blockLocatorBuilder;
    private final BlockFailureResolver failureResolver;

    public NodeSyncInfrastructure(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        this(database, parameters, adjustedTime, null);
    }

    /** Reuse the validation index cache as Core reuses its global block index. */
    public NodeSyncInfrastructure(RocksDbDatabase database, NetworkParameters parameters,
                                  AdjustedTime adjustedTime, StoredBlockIndexLookup sharedLookup) {
        Objects.requireNonNull(
                database,
                "database"
        );

        Objects.requireNonNull(
                parameters,
                "parameters"
        );

        Objects.requireNonNull(
                adjustedTime,
                "adjustedTime"
        );

        RocksDbBlockIndexStore blockIndexStore =
                new RocksDbBlockIndexStore(
                        database
                );

        RocksDbChainStateStore chainStateStore =
                new RocksDbChainStateStore(
                        database
                );

        // One-time, restart-safe acceleration index for Bitcoin Core-style GetAncestor.
        blockIndexStore.ensureSkipIndex();

        this.blockStore =
                new RocksDbBlockStore(
                        database
                );

        this.blockIndexLookup = sharedLookup != null ? sharedLookup
                : new StoredBlockIndexLookup(blockIndexStore);

        this.headerChainState =
                new HeaderChainStateLoader(
                        blockIndexStore,
                        chainStateStore,
                        blockIndexLookup
                )
                        .load()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Best header state was not initialized"
                                        )
                        );

        this.failureResolver =
                new BlockFailureResolver(
                        blockIndexLookup,
                        new RocksDbBlockFailureStore(database)
                );

        /*
         * BlockFailureManager never persists a failed ancestry as best-header.
         * Seed that durable invariant into the resolver so the first HEADER after
         * restart can stop at the persisted tip instead of scanning to genesis.
         */
        failureResolver.seedKnownValid(
                this.headerChainState.bestHeaderTip()
        );

        HeaderProcessor headerProcessor =
                new HeaderProcessor(
                        blockIndexLookup,
                        parameters,
                        adjustedTime
                );

        KnownHeaderStorage headerStorage =
                new KnownHeaderStorage(
                        database,
                        blockIndexStore,
                        chainStateStore
                );

        HeaderBatchProcessor batchProcessor =
                new HeaderBatchProcessor(
                        headerProcessor,
                        headerChainState,
                        headerStorage,
                        failureResolver
                );

        this.headerSyncService =
                new HeaderSyncService(
                        batchProcessor
                );

        this.blockLocatorBuilder =
                new BlockLocatorBuilder(
                        blockIndexLookup
                );
    }

    public BlockIndexLookup blockIndexLookup() {
        return blockIndexLookup;
    }

    public RocksDbBlockStore blockStore() {
        return blockStore;
    }

    public HeaderChainState headerChainState() {
        return headerChainState;
    }

    public HeaderSyncService headerSyncService() {
        return headerSyncService;
    }

    public BlockLocatorBuilder blockLocatorBuilder() {
        return blockLocatorBuilder;
    }

    public boolean downloadBlockFailed(BlockIndex index) {
        return failureResolver.isFailed(index);
    }
}
