package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.app.HeaderSyncService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.network.NetworkParameters;
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

    public NodeSyncInfrastructure(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
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

        this.blockStore =
                new RocksDbBlockStore(
                        database
                );

        this.blockIndexLookup =
                new StoredBlockIndexLookup(
                        blockIndexStore
                );

        this.headerChainState =
                new HeaderChainStateLoader(
                        blockIndexStore,
                        chainStateStore
                )
                        .load()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Best header state was not initialized"
                                        )
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
                        headerStorage
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
}