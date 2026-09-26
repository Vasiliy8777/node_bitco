package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.Arrays;
import java.util.Objects;

import static ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize;

/** Startup-only: call before publishing ChainState or starting block processing. */
public final class ChainInitializer {
    private final RocksDbDatabase database;
    private final NetworkParameters parameters;

    public ChainInitializer(RocksDbDatabase database, NetworkParameters parameters) {
        this.database = Objects.requireNonNull(database, "database");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public ChainState initialize() {
        synchronized (database) {
            Block genesis = GenesisBlockFactory.create(parameters);
            BlockIndex genesisIndex = BlockIndexFactory.createGenesis(genesis.header());
            var blocks = new RocksDbBlockStore(database);
            var indexes = new RocksDbBlockIndexStore(database);
            var tips = new RocksDbChainStateStore(database);
            var loaded = new ChainStateLoader(indexes, tips).load();
            if (loaded.isPresent()) {
                ChainState state = loaded.orElseThrow();
                validateAncestry(state.activeTip(), genesisIndex, new StoredBlockIndexLookup(indexes));
                Block storedGenesis = blocks.find(genesis.hash()).orElseThrow(
                        () -> new IllegalStateException("Missing genesis block body"));
                if (!Arrays.equals(serialize(genesis), serialize(storedGenesis))) {
                    throw new IllegalStateException("Invalid stored genesis block");
                }
                if (blocks.find(state.activeTip().hash()).isEmpty()) {
                    throw new IllegalStateException("Missing active tip block body");
                }

                if (tips.loadBestHeaderTipHash().isEmpty()) {

                    /*
                     * Database created before separate
                     * best-header-tip persistence existed.
                     *
                     * The active tip is always a fully validated
                     * known header, therefore it is a safe
                     * migration starting point.
                     */
                    tips.saveBestHeaderTipHash(
                            state.activeTip().hash()
                    );
                }

                return state;
            }
            if (!database.isEmpty()) {
                throw new IllegalStateException("Nonempty database has no active tip; recovery is required");
            }
            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {

                blocks.save(
                        batch,
                        genesis
                );
                new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database)
                        .markData(batch, genesis.hash());
                new ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore(database)
                        .markScriptsValid(batch, genesis.hash());

                indexes.save(
                        batch,
                        BlockIndexStorageMapper.toStored(
                                genesisIndex
                        )
                );

                tips.saveActiveTipHash(
                        batch,
                        genesis.hash()
                );

                tips.saveBestHeaderTipHash(
                        batch,
                        genesis.hash()
                );

                database.write(
                        batch
                );
            }
            // Genesis is an anchor: its coinbase output must never enter the UTXO set.
            return new ChainState(genesisIndex);
        }
    }

    private static void validateAncestry(BlockIndex tip, BlockIndex genesis, BlockIndexLookup lookup) {
        BlockIndex current = tip;
        while (current.height() > 0) {
            if (!current.hash().equals(current.header().hash())
                    || !current.previousBlockHash().equals(current.header().previousBlockHash())) {
                throw new IllegalStateException("Inconsistent stored block index");
            }
            BlockIndex parent = lookup.find(current.previousBlockHash());
            if (parent == null || parent.height() != current.height() - 1) {
                throw new IllegalStateException("Missing or inconsistent active-chain ancestor");
            }
            BlockIndex expected = BlockIndexFactory.createChild(parent, current.header());
            if (!expected.chainWork().equals(current.chainWork())) {
                throw new IllegalStateException("Inconsistent accumulated chain work");
            }
            current = parent;
        }
        if (!BlockIndexStorageMapper.toStored(current).equals(BlockIndexStorageMapper.toStored(genesis))) {
            throw new IllegalStateException("Stored chain does not match selected network genesis");
        }
    }
}
