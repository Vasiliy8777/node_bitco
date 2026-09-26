package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.chain.RocksDbFullReindexStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FullReindexerTest {
    private static final NetworkParameters REGTEST = NetworkParametersRegistry.regtest();
    private static final AdjustedTime TIME = () -> 1_800_000_000L;
    private static final long BITS = 0x207fffffL;
    private static final long REWARD = 5_000_000_000L;
    @TempDir
    Path directory;

    @Test
    void rebuildsGenesisIndexFromRawBlockNamespace() {
        try (var db = new RocksDbDatabase(directory)) {
            var initialized = new ChainInitializer(db, REGTEST).initialize();
            var indexes = new RocksDbBlockIndexStore(db);
            indexes.delete(initialized.activeTip().hash());

            var reindexer = new FullReindexer(db, REGTEST, TIME);
            var result = reindexer.rebuild();

            assertEquals(GenesisBlockFactory.create(REGTEST).hash(), result.activeTipHash());
            assertEquals(0L, result.activeHeight());
            assertEquals(1L, result.discoveredBlockBodies());
            assertTrue(indexes.find(result.activeTipHash()).isPresent());
            assertFalse(new RocksDbFullReindexStateStore(db).isInProgress());
        }
    }
    @Test
    void skipsNewlyDiscoveredStructurallyInvalidSideBranchAndPersistsFailure() {
        try (var db = new RocksDbDatabase(directory)) {
            ChainState state = new ChainInitializer(db, REGTEST).initialize();
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            var tips = new RocksDbChainStateStore(db);
            var utxos = new RocksDbUtxoStore(db);
            var undos = new RocksDbUndoStore(db);
            var lookup = new StoredBlockIndexLookup(indexes);
            var known = new KnownBlockStorage(db, blocks, indexes);
            var transitions = new RocksDbChainTransitionStorage(db, utxos, undos, indexes, tips);
            var executor = new ChainReorganizationExecutor(
                    blocks, undos, utxos,
                    new ChainTransitionManager(state, transitions), REGTEST, lookup);
            var processor = new BlockProcessor(state, lookup, known, executor, REGTEST, TIME);

            Block validMain = child(state.activeTip(), 1, REWARD);
            assertEquals(BlockProcessingResult.CONNECTED, processor.process(validMain));

            // Persist a raw side-chain body whose header has valid regtest PoW but whose
            // merkle root deliberately does not commit to the serialized transaction body.
            Block validSideTemplate = child(
                    BlockIndexFactory.createGenesis(GenesisBlockFactory.create(REGTEST).header()),
                    2, REWARD);
            Hash256 wrongRoot = Hash256.fromDisplayHex("11".repeat(32));
            Block invalidSide = withHeader(
                    new Block(validSideTemplate.header(), validSideTemplate.transactions()),
                    validSideTemplate.header().previousBlockHash(), wrongRoot, true);
            blocks.save(invalidSide);

            assertFalse(new RocksDbBlockFailureStore(db).isFailed(invalidSide.hash()));
            assertTrue(indexes.find(invalidSide.hash()).isEmpty());

            var result = new FullReindexer(db, REGTEST, TIME).rebuild();

            assertEquals(validMain.hash(), result.activeTipHash());
            assertEquals(1L, result.activeHeight());
            assertTrue(result.skippedFailedBlocks() >= 1);
            assertTrue(new RocksDbBlockFailureStore(db).isFailed(invalidSide.hash()));
            assertTrue(indexes.find(invalidSide.hash()).isPresent());
            assertTrue(new RocksDbBlockAvailabilityStore(db).hasData(invalidSide.hash()));
            assertFalse(new RocksDbFullReindexStateStore(db).isInProgress());
        }
    }

    @Test
    void keepsActualContextualFailureRootWhenReplayDiscoversInvalidSideBranch() {
        try (var db = new RocksDbDatabase(directory.resolve("contextual"))) {
            ChainState state = new ChainInitializer(db, REGTEST).initialize();
            var blocks = new RocksDbBlockStore(db);
            var indexes = new RocksDbBlockIndexStore(db);
            var tips = new RocksDbChainStateStore(db);
            var utxos = new RocksDbUtxoStore(db);
            var undos = new RocksDbUndoStore(db);
            var lookup = new StoredBlockIndexLookup(indexes);
            var known = new KnownBlockStorage(db, blocks, indexes);
            var transitions = new RocksDbChainTransitionStorage(db, utxos, undos, indexes, tips);
            var executor = new ChainReorganizationExecutor(
                    blocks, undos, utxos,
                    new ChainTransitionManager(state, transitions), REGTEST, lookup);
            var processor = new BlockProcessor(state, lookup, known, executor, REGTEST, TIME);

            Block validMain = child(state.activeTip(), 10, REWARD);
            assertEquals(BlockProcessingResult.CONNECTED, processor.process(validMain));

            // Equal-work side block is stored without contextual connection. Its excessive
            // coinbase reward becomes provably invalid only when a stronger descendant asks
            // the reorg executor to connect the branch.
            Block invalidAncestor = child(
                    BlockIndexFactory.createGenesis(GenesisBlockFactory.create(REGTEST).header()),
                    11, REWARD + 1);
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING,
                    processor.process(invalidAncestor));
            BlockIndex invalidIndex = indexes.find(invalidAncestor.hash())
                    .map(BlockIndexStorageMapper::fromStored).orElseThrow();
            Block descendant = child(invalidIndex, 12, REWARD);
            blocks.save(descendant); // raw body only: failure has not yet been discovered

            var failures = new RocksDbBlockFailureStore(db);
            assertFalse(failures.isFailed(invalidAncestor.hash()));
            assertFalse(failures.isFailed(descendant.hash()));

            var result = new FullReindexer(db, REGTEST, TIME).rebuild();

            assertEquals(validMain.hash(), result.activeTipHash());
            assertTrue(failures.isFailed(invalidAncestor.hash()));
            assertFalse(failures.isFailed(descendant.hash()),
                    "reindex must preserve the observer-selected invalid root, not invent a descendant root");
            assertTrue(indexes.find(descendant.hash()).isPresent());
            assertTrue(result.skippedFailedBlocks() >= 1);
        }
    }

    private static Block child(BlockIndex parent, int tag, long reward) {
        int height = Math.toIntExact(parent.height() + 1);
        Transaction coinbase = new Transaction(1,
                List.of(new TxIn(OutPoint.coinbase(), new byte[]{(byte) (0x50 + height), (byte) tag},
                        TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(reward, new byte[]{0x51})), new UInt32(0));
        Hash256 root = MerkleTree.calculateRoot(List.of(coinbase.txId()));
        Block block = new Block(new BlockHeader(4, parent.hash(), root,
                new UInt32(parent.header().timestamp().value() + 1), new UInt32(BITS), new UInt32(0)),
                List.of(coinbase));
        return withHeader(block, parent.hash(), root, true);
    }

    private static Block withHeader(Block block, Hash256 parent, Hash256 root, boolean validPow) {
        for (long nonce = 0; nonce < 100_000; nonce++) {
            BlockHeader header = new BlockHeader(4, parent, root, block.header().timestamp(),
                    new UInt32(BITS), new UInt32(nonce));
            if (ProofOfWork.isValid(header, REGTEST) == validPow) {
                return new Block(header, block.transactions());
            }
        }
        throw new AssertionError("Could not construct regtest header");
    }

}
