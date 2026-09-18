package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.protocol.serialization.BlockSerializer.serialize;

class ChainInitializerTest {
    @TempDir
    Path path;

    @Test
    void initializesEmptyDatabaseWithoutGenesisUtxoOrUndo() {
        try (var db = new RocksDbDatabase(path)) {
            assertTrue(db.isEmpty());
            var state = initializer(db).initialize();
            var genesis = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            assertEquals(genesis.hash(), state.activeTip().hash());
            assertEquals(0, state.activeTip().height());
            assertEquals(BlockIndexFactory.createGenesis(genesis.header()).chainWork(), state.activeTip().chainWork());
            assertArrayEquals(serialize(genesis), serialize(new RocksDbBlockStore(db).find(genesis.hash()).orElseThrow()));
            assertEquals(genesis.hash(), new RocksDbChainStateStore(db).loadActiveTipHash().orElseThrow());
            assertTrue(new RocksDbBlockIndexStore(db).find(genesis.hash()).isPresent());
            assertTrue(new RocksDbUtxoStore(db).find(new OutPoint(genesis.transactions().getFirst().txId(), new UInt32(0))).isEmpty());
            assertTrue(new RocksDbUndoStore(db).find(genesis.hash()).isEmpty());
            assertFalse(db.isEmpty());
        }
    }

    @Test
    void initializationIsRepeatableAcrossReopen() {
        Hash256 hash;
        try (var db = new RocksDbDatabase(path)) {
            hash = initializer(db).initialize().activeTip().hash();
            assertEquals(hash, initializer(db).initialize().activeTip().hash());
        }
        try (var db = new RocksDbDatabase(path)) {
            assertEquals(hash, initializer(db).initialize().activeTip().hash());
        }
    }

    @Test
    void rejectsDifferentNetworkWithoutOverwritingTip() {
        try (var db = new RocksDbDatabase(path)) {
            Hash256 original = initializer(db).initialize().activeTip().hash();
            assertThrows(IllegalStateException.class,
                    () -> new ChainInitializer(db, NetworkParametersRegistry.mainnet()).initialize());
            assertEquals(original, new RocksDbChainStateStore(db).loadActiveTipHash().orElseThrow());
            assertTrue(new RocksDbBlockStore(db).find(NetworkParametersRegistry.mainnet().genesisBlockHash()).isEmpty());
        }
    }

    @Test
    void refusesPartialDatabaseWithNoTip() {
        try (var db = new RocksDbDatabase(path)) {
            var genesis = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
            new RocksDbBlockStore(db).save(genesis);
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
            assertTrue(new RocksDbChainStateStore(db).loadActiveTipHash().isEmpty());
            assertTrue(new RocksDbBlockIndexStore(db).find(genesis.hash()).isEmpty());
        }
    }

    @Test
    void refusesUnrelatedContentsWithNoTip() {
        try (var db = new RocksDbDatabase(path)) {
            db.put(new byte[]{99}, new byte[]{42});
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
            assertArrayEquals(new byte[]{42}, db.get(new byte[]{99}));
            assertTrue(new RocksDbChainStateStore(db).loadActiveTipHash().isEmpty());
        }
    }

    @Test
    void rejectsTipWithMissingIndex() {
        try (var db = new RocksDbDatabase(path)) {
            var tip = initializer(db).initialize().activeTip();
            new RocksDbBlockIndexStore(db).delete(tip.hash());
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
            assertTrue(new RocksDbBlockIndexStore(db).find(tip.hash()).isEmpty());
        }
    }

    @Test
    void rejectsMissingGenesisBody() {
        try (var db = new RocksDbDatabase(path)) {
            var tip = initializer(db).initialize().activeTip();
            new RocksDbBlockStore(db).delete(tip.hash());
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
            assertTrue(new RocksDbBlockStore(db).find(tip.hash()).isEmpty());
        }
    }

    @Test
    void rejectsCorruptGenesisChainWork() {
        try (var db = new RocksDbDatabase(path)) {
            var tip = initializer(db).initialize().activeTip();
            new RocksDbBlockIndexStore(db).save(new StoredBlockIndex(tip.hash(), tip.header(), 0,
                    tip.previousBlockHash(), tip.chainWork().add(BigInteger.ONE)));
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
        }
    }

    @Test
    void rejectsMalformedTipValue() {
        try (var db = new RocksDbDatabase(path)) {
            initializer(db).initialize();
            db.put(new byte[]{0x02, 0x01}, new byte[]{1});
            assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
        }
    }

    @Test
    void migratesLegacyDatabaseWithoutBestHeaderTip() {

        try (var db = new RocksDbDatabase(path)) {

            var genesis =
                    GenesisBlockFactory.create(
                            NetworkParametersRegistry.regtest()
                    );

            var genesisIndex =
                    BlockIndexFactory.createGenesis(
                            genesis.header()
                    );

            var blocks =
                    new RocksDbBlockStore(db);

            var indexes =
                    new RocksDbBlockIndexStore(db);

            var tips =
                    new RocksDbChainStateStore(db);

            /*
             * Simulate database created by the old node version:
             *
             * - genesis block exists
             * - genesis block index exists
             * - active tip exists
             * - best header tip does not exist
             */
            blocks.save(
                    genesis
            );

            indexes.save(
                    BlockIndexStorageMapper.toStored(
                            genesisIndex
                    )
            );

            tips.saveActiveTipHash(
                    genesis.hash()
            );

            assertTrue(
                    tips.loadBestHeaderTipHash()
                            .isEmpty()
            );

            ChainState state =
                    initializer(db)
                            .initialize();

            assertEquals(
                    genesis.hash(),
                    state.activeTip().hash()
            );

            assertEquals(
                    genesis.hash(),
                    tips.loadActiveTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    genesis.hash(),
                    tips.loadBestHeaderTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void closedDatabaseCannotBeInitialized() {
        var db = new RocksDbDatabase(path);
        db.close();
        assertThrows(IllegalStateException.class, () -> initializer(db).initialize());
    }

    private static ChainInitializer initializer(RocksDbDatabase db) {
        return new ChainInitializer(db, NetworkParametersRegistry.regtest());
    }
}
