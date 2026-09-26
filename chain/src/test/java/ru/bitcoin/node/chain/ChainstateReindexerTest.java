package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.chain.RocksDbReindexStateStore;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.List;

import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChainstateReindexerTest {
    private static final NetworkParameters REGTEST = NetworkParametersRegistry.regtest();
    private static final AdjustedTime TIME = () -> 1_800_000_000L;

    @TempDir
    Path tempDirectory;

    @Test
    void reindexOnFreshDatabaseInitializesGenesis() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("fresh"))) {
            var reindexer = new ChainstateReindexer(database, REGTEST, TIME);
            var result = reindexer.rebuild();
            var genesis = GenesisBlockFactory.create(REGTEST);
            assertEquals(genesis.hash(), result.tipHash());
            assertEquals(0L, result.height());
            assertEquals(0L, result.replayedBlocks());
            assertFalse(reindexer.isInProgress());
            assertEquals(genesis.hash(), new RocksDbChainStateStore(database)
                    .loadActiveTipHash().orElseThrow());
        }
    }

    @Test
    void genesisOnlyChainCanBeRebuiltWithoutLosingBestHeaderMetadata() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("genesis"))) {
            var state = new ChainInitializer(database, REGTEST).initialize();
            var reindexer = new ChainstateReindexer(database, REGTEST, TIME);
            var result = reindexer.rebuild();
            assertEquals(state.activeTip().hash(), result.tipHash());
            assertEquals(0L, result.replayedBlocks());
            assertEquals(state.activeTip().hash(), new RocksDbChainStateStore(database)
                    .loadBestHeaderTipHash().orElseThrow());
            assertTrue(new RocksDbReindexStateStore(database).loadTargetTipHash().isEmpty());
        }
    }


    @Test
    void chainstateResetClearsStaleUndoAvailabilityBeforeReplay() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("undo-flags"))) {
            var state = new ChainInitializer(database, REGTEST).initialize();
            var hash = state.activeTip().hash();
            var undos = new RocksDbUndoStore(database);
            var availability = new RocksDbBlockAvailabilityStore(database);

            // Genesis never needs undo, but seed a stale payload/flag to model pre-reset state.
            undos.save(hash, new BlockUndoData(List.of()));
            assertTrue(availability.hasData(hash));
            assertTrue(availability.hasUndo(hash));

            new ChainstateReindexer(database, REGTEST, TIME).rebuild();

            assertTrue(availability.hasData(hash));
            assertFalse(availability.hasUndo(hash));
            assertTrue(undos.find(hash).isEmpty());
        }
    }

    @Test
    void refusesNonemptyDatabaseWithoutTipOrRecoveryMarkerBeforeDestructiveReset() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("partial"))) {
            database.put(new byte[]{0x55, 0x01}, new byte[]{0x01});
            var reindexer = new ChainstateReindexer(database, REGTEST, TIME);
            assertThrows(IllegalStateException.class, reindexer::rebuild);
            assertArrayEquals(new byte[]{0x01}, database.get(new byte[]{0x55, 0x01}));
        }
    }
}
