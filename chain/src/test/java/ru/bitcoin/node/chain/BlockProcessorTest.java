package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.consensus.block.BlockValidationException;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.chain.BlockProcessingResult.*;

class BlockProcessorTest {
    private static final NetworkParameters PARAMETERS = NetworkParametersRegistry.regtest();
    private static final long TIME = 1_700_000_000L;
    private static final long BITS = 0x207fffffL;
    private static final long REWARD = 5_000_000_000L;
    private static final Hash256 ZERO = new Hash256(new byte[32]);

    @TempDir
    Path directory;

    @Test
    void connectsBlockAndRestoresPersistentState() {
        Block block;
        try (Fixture f = new Fixture(directory)) {
            block = child(f.genesis, 1, REWARD);
            assertEquals(CONNECTED, f.processor.process(block));
            f.assertTip(block.hash());
            assertTrue(f.utxos.find(output(block)).isPresent());
            assertTrue(f.undos.find(block.hash()).isPresent());
        }
        try (Fixture f = new Fixture(directory)) {
            f.assertTip(block.hash());
            assertTrue(f.utxos.find(output(block)).isPresent());
            assertTrue(f.undos.find(block.hash()).isPresent());
            assertEquals(ALREADY_IN_ACTIVE_CHAIN, f.processor.process(block));
            Block next = child(f.state.activeTip(), 2, REWARD);
            assertEquals(CONNECTED, f.processor.process(next));
            f.assertTip(next.hash());
        }
    }

    @Test
    void receivingKnownActiveBlockRestoresPrunedBody() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            assertEquals(CONNECTED, f.processor.process(block));
            f.blocks.delete(block.hash());
            assertTrue(f.blocks.find(block.hash()).isEmpty());
            assertEquals(ALREADY_IN_ACTIVE_CHAIN, f.processor.process(block));
            assertTrue(f.blocks.find(block.hash()).isPresent());
            f.assertTip(block.hash());
        }
    }

    @Test
    void initializerRejectsMissingAncestorOfConnectedTip() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.processor.process(block);
            f.indexes.delete(f.genesis.hash());
            assertThrows(IllegalStateException.class, () -> new ChainInitializer(f.database, PARAMETERS).initialize());
            f.assertTip(block.hash());
        }
    }

    @Test
    void initializerRejectsIncorrectAccumulatedWork() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.processor.process(block);
            var stored = f.indexes.find(block.hash()).orElseThrow();
            f.indexes.save(new ru.bitcoin.node.storage.block.StoredBlockIndex(stored.hash(), stored.header(),
                    stored.height(), stored.previousBlockHash(), stored.chainWork().add(java.math.BigInteger.ONE)));
            assertThrows(IllegalStateException.class, () -> new ChainInitializer(f.database, PARAMETERS).initialize());
            f.assertTip(block.hash());
        }
    }

    @Test
    void initializerRejectsMissingTipBody() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.processor.process(block);
            f.blocks.delete(block.hash());
            assertThrows(IllegalStateException.class, () -> new ChainInitializer(f.database, PARAMETERS).initialize());
            f.assertTip(block.hash());
        }
    }

    @Test
    void duplicateTipAndAncestorDoNotChangeState() {
        try (Fixture f = new Fixture(directory)) {
            Block first = child(f.genesis, 1, REWARD);
            f.processor.process(first);
            Block second = child(f.state.activeTip(), 2, REWARD);
            f.processor.process(second);
            assertEquals(ALREADY_IN_ACTIVE_CHAIN, f.processor.process(first));
            assertEquals(ALREADY_IN_ACTIVE_CHAIN, f.processor.process(second));
            f.assertTip(second.hash());
            assertTrue(f.utxos.find(output(first)).isPresent());
        }
    }

    @Test
    void unknownParentIsNotStored() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            Block orphan = withHeader(block, ZERO, block.header().merkleRoot(), BITS, true);
            assertEquals(UNKNOWN_PARENT, f.processor.process(orphan));
            assertTrue(f.blocks.find(orphan.hash()).isEmpty());
            assertNull(f.lookup.find(orphan.hash()));
            f.assertTip(f.genesis.hash());
        }
    }

    @Test
    void rejectsInvalidProofOfWorkBeforeSaving() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            Block invalid = withHeader(block, f.genesis.hash(), block.header().merkleRoot(), BITS, false);
            assertThrows(BlockHeaderValidationException.class, () -> f.processor.process(invalid));
            assertTrue(f.blocks.find(invalid.hash()).isEmpty());
            f.assertTip(f.genesis.hash());
        }
    }

    @Test
    void rejectsUnexpectedBitsEvenWithValidProofOfWork() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            Block invalid = withHeader(block, f.genesis.hash(), block.header().merkleRoot(), 0x203fffffL, true);
            assertThrows(BlockHeaderValidationException.class, () -> f.processor.process(invalid));
            assertTrue(f.blocks.find(invalid.hash()).isEmpty());
            f.assertTip(f.genesis.hash());
        }
    }

    @Test
    void rejectsInvalidMerkleRootBeforeSaving() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            Block invalid = withHeader(block, f.genesis.hash(), ZERO, BITS, true);
            assertThrows(BlockValidationException.class, () -> f.processor.process(invalid));
            assertTrue(f.blocks.find(invalid.hash()).isEmpty());
        }
    }

    @Test
    void validatesBodyEvenWhenHeaderIsAlreadyActive() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.processor.process(block);
            Block changedBody = new Block(block.header(), child(f.genesis, 2, REWARD).transactions());
            assertThrows(BlockValidationException.class, () -> f.processor.process(changedBody));
            f.assertTip(block.hash());
            assertEquals(block.transactions(), f.blocks.find(block.hash()).orElseThrow().transactions());
        }
    }

    @Test
    void storesEqualWorkBranchThenReorganizesToStrongerBranch() {
        try (Fixture f = new Fixture(directory)) {
            Block main = child(f.genesis, 1, REWARD);
            f.processor.process(main);
            Block side = child(f.genesis, 2, REWARD);
            assertEquals(STORED_SIDE_CHAIN_CONTEXT_PENDING, f.processor.process(side));
            assertEquals(STORED_SIDE_CHAIN_CONTEXT_PENDING, f.processor.process(side));
            f.assertTip(main.hash());
            assertTrue(f.utxos.find(output(side)).isEmpty());
            Block next = child(f.lookup.find(side.hash()), 3, REWARD);
            assertEquals(CONNECTED, f.processor.process(next));
            f.assertTip(next.hash());
            assertTrue(f.utxos.find(output(main)).isEmpty());
            assertTrue(f.utxos.find(output(side)).isPresent());
            assertTrue(f.utxos.find(output(next)).isPresent());
        }
    }

    @Test
    void rejectsAddedWitnessEvenWhenHeaderAndTxidAreAlreadyActive() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.processor.process(block);
            Transaction coinbase = block.transactions().getFirst();
            TxIn input = coinbase.inputs().getFirst();
            Transaction changed = new Transaction(coinbase.version(),
                    List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                            new Witness(List.of(new byte[32])))), coinbase.outputs(), coinbase.lockTime());
            assertEquals(coinbase.txId(), changed.txId());
            Block invalid = new Block(block.header(), List.of(changed));
            assertThrows(BlockValidationException.class, () -> f.processor.process(invalid));
            f.assertTip(block.hash());
            assertEquals(block.transactions(), f.blocks.find(block.hash()).orElseThrow().transactions());
            assertTrue(f.utxos.find(output(block)).isPresent());
        }
    }

    @Test
    void rejectsInvalidTaprootSignatureWithValidCommitmentWithoutChangingUtxos() {
        try (Fixture f = new Fixture(directory)) {
            OutPoint funding = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
            var original = new ru.bitcoin.node.storage.utxo.StoredUtxo(1000,
                    java.util.HexFormat.of().parseHex("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
                    0, false);
            f.utxos.save(funding, original);
            Transaction spend = new Transaction(2, List.of(new TxIn(funding, new byte[0],
                    TxIn.FINAL_SEQUENCE, new Witness(List.of(new byte[64])))),
                    List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
            byte[] commitmentPreimage = new byte[64];
            System.arraycopy(MerkleTree.calculateRoot(List.of(ZERO, spend.wtxId())).bytes(),
                    0, commitmentPreimage, 0, 32);
            byte[] commitment = new byte[38];
            System.arraycopy(java.util.HexFormat.of().parseHex("6a24aa21a9ed"), 0, commitment, 0, 6);
            System.arraycopy(ru.bitcoin.node.crypto.hash.Hash256Digest.hash(commitmentPreimage).bytes(),
                    0, commitment, 6, 32);
            Block template = child(f.genesis, 1, REWARD);
            TxIn input = template.transactions().getFirst().inputs().getFirst();
            Transaction coinbase = new Transaction(1,
                    List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                            new Witness(List.of(new byte[32])))),
                    List.of(new TxOut(REWARD, new byte[]{0x51}), new TxOut(0, commitment)), new UInt32(0));
            Hash256 root = MerkleTree.calculateRoot(List.of(coinbase.txId(), spend.txId()));
            Block invalid = withHeader(new Block(template.header(), List.of(coinbase, spend)),
                    f.genesis.hash(), root, BITS, true);
            assertDoesNotThrow(() -> ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(invalid, true));
            assertThrows(ru.bitcoin.node.script.ScriptExecutionException.class, () -> f.processor.process(invalid));
            f.assertTip(f.genesis.hash());
            assertEquals(original, f.utxos.find(funding).orElseThrow());
            assertTrue(f.utxos.find(output(invalid)).isEmpty());
            assertTrue(f.utxos.find(new OutPoint(spend.txId(), new UInt32(0))).isEmpty());
            assertTrue(f.undos.find(invalid.hash()).isEmpty());
        }
    }

    @Test
    void contextuallyInvalidBlockIsRejectedAgainAfterRestart() {
        Block invalid;
        try (Fixture f = new Fixture(directory)) {
            invalid = child(f.genesis, 1, REWARD + 1);
            assertThrows(BlockValidationException.class, () -> f.processor.process(invalid));
            assertTrue(f.blocks.find(invalid.hash()).isPresent());
            f.assertTip(f.genesis.hash());
            assertTrue(f.utxos.find(output(invalid)).isEmpty());
            assertTrue(f.undos.find(invalid.hash()).isEmpty());
        }
        try (Fixture f = new Fixture(directory)) {
            assertThrows(BlockValidationException.class, () -> f.processor.process(invalid));
            f.assertTip(f.genesis.hash());
        }
    }

    @Test
    void invalidSideBranchDoesNotDisconnectValidChain() {
        try (Fixture f = new Fixture(directory)) {
            Block main = child(f.genesis, 1, REWARD);
            f.processor.process(main);
            Block invalidSide = child(f.genesis, 2, REWARD + 1);
            assertEquals(STORED_SIDE_CHAIN_CONTEXT_PENDING, f.processor.process(invalidSide));
            Block next = child(f.lookup.find(invalidSide.hash()), 3, REWARD);
            assertThrows(BlockValidationException.class, () -> f.processor.process(next));
            f.assertTip(main.hash());
            assertTrue(f.utxos.find(output(main)).isPresent());
            assertTrue(f.undos.find(main.hash()).isPresent());
            assertTrue(f.utxos.find(output(invalidSide)).isEmpty());
            assertTrue(f.utxos.find(output(next)).isEmpty());
        }
    }

    @Test
    void retriesStoredBlockWhoseActivationWasInterrupted() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            f.known.save(block, BlockIndexFactory.createChild(f.genesis, block.header()));
            assertEquals(CONNECTED, f.processor.process(block));
            f.assertTip(block.hash());
        }
    }

    @Test
    void storageWriteFailureDoesNotChangeMemory() {
        try (Fixture f = new Fixture(directory)) {
            Block block = child(f.genesis, 1, REWARD);
            try (RocksDbDatabase closed = new RocksDbDatabase(directory.resolve("closed"))) {
                KnownBlockStorage broken = new KnownBlockStorage(closed,
                        new RocksDbBlockStore(closed), new RocksDbBlockIndexStore(closed));
                closed.close();
                BlockProcessor processor = f.processor(broken);
                assertThrows(IllegalStateException.class, () -> processor.process(block));
                f.assertTip(f.genesis.hash());
                assertTrue(f.blocks.find(block.hash()).isEmpty());
            }
        }
    }

    @Test
    void concurrentDuplicateSubmissionsConnectOnlyOnce() throws Exception {
        try (Fixture f = new Fixture(directory);
             var workers = Executors.newFixedThreadPool(2)) {
            Block block = child(f.genesis, 1, REWARD);
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> { start.await(); return f.processor.process(block); });
            var second = workers.submit(() -> { start.await(); return f.processor.process(block); });
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(results.contains(CONNECTED));
            assertTrue(results.contains(ALREADY_IN_ACTIVE_CHAIN));
            f.assertTip(block.hash());
        }
    }

    private static OutPoint output(Block block) {
        return new OutPoint(block.transactions().getFirst().txId(), new UInt32(0));
    }

    @Test
    void rejectsMalformedWrappedWitnessWithoutChangingChainState() {
        try (Fixture f = new Fixture(directory)) {
            byte[] redeem = new byte[22];
            redeem[1] = 20;
            java.util.Arrays.fill(redeem, 2, redeem.length, (byte) 1);
            byte[] lockingScript = new byte[23];
            lockingScript[0] = (byte) 0xa9;
            lockingScript[1] = 20;
            System.arraycopy(ru.bitcoin.node.crypto.hash.Hash160.hash(redeem), 0, lockingScript, 2, 20);
            lockingScript[22] = (byte) 0x87;
            OutPoint funding = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
            var original = new ru.bitcoin.node.storage.utxo.StoredUtxo(1000, lockingScript, 0, false);
            f.utxos.save(funding, original);
            byte[] scriptSig = new byte[24];
            scriptSig[0] = 0x4c;
            scriptSig[1] = 22;
            System.arraycopy(redeem, 0, scriptSig, 2, 22);
            Transaction spend = new Transaction(2, List.of(new TxIn(funding, scriptSig, TxIn.FINAL_SEQUENCE)),
                    List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
            Block template = child(f.genesis, 1, REWARD);
            Transaction coinbase = template.transactions().getFirst();
            Hash256 root = MerkleTree.calculateRoot(List.of(coinbase.txId(), spend.txId()));
            Block invalid = withHeader(new Block(template.header(), List.of(coinbase, spend)), f.genesis.hash(), root, BITS, true);
            assertThrows(ru.bitcoin.node.consensus.transaction.TransactionValidationException.class,
                    () -> f.processor.process(invalid));
            f.assertTip(f.genesis.hash());
            assertEquals(original, f.utxos.find(funding).orElseThrow());
            assertTrue(f.utxos.find(output(invalid)).isEmpty());
            assertTrue(f.undos.find(invalid.hash()).isEmpty());
        }
    }

    private static Block child(BlockIndex parent, int tag, long reward) {
        int height = Math.toIntExact(parent.height() + 1);
        Transaction coinbase = new Transaction(1,
                List.of(new TxIn(OutPoint.coinbase(), new byte[]{(byte) (0x50 + height), (byte) tag},
                        TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(reward, new byte[]{0x51})), new UInt32(0));
        List<Transaction> transactions = List.of(coinbase);
        Hash256 root = MerkleTree.calculateRoot(List.of(coinbase.txId()));
        Block block = new Block(new BlockHeader(4, parent.hash(), root,
                new UInt32(parent.header().timestamp().value() + 1), new UInt32(BITS), new UInt32(0)), transactions);
        return withHeader(block, parent.hash(), root, BITS, true);
    }

    private static Block withHeader(Block block, Hash256 parent, Hash256 root, long bits, boolean validPow) {
        for (long nonce = 0; nonce < 100_000; nonce++) {
            BlockHeader header = new BlockHeader(4, parent, root, block.header().timestamp(),
                    new UInt32(bits), new UInt32(nonce));
            if (ProofOfWork.isValid(header, PARAMETERS) == validPow) {
                return new Block(header, block.transactions());
            }
        }
        throw new AssertionError("Could not construct regtest header");
    }

    private static final class Fixture implements AutoCloseable {
        final RocksDbDatabase database;
        final RocksDbBlockStore blocks;
        final RocksDbBlockIndexStore indexes;
        final RocksDbChainStateStore tips;
        final RocksDbUtxoStore utxos;
        final RocksDbUndoStore undos;
        final KnownBlockStorage known;
        final BlockIndexLookup lookup;
        final BlockIndex genesis;
        final ChainState state;
        final ChainReorganizationExecutor executor;
        final BlockProcessor processor;

        Fixture(Path path) {
            database = new RocksDbDatabase(path);
            blocks = new RocksDbBlockStore(database);
            indexes = new RocksDbBlockIndexStore(database);
            tips = new RocksDbChainStateStore(database);
            utxos = new RocksDbUtxoStore(database);
            undos = new RocksDbUndoStore(database);
            known = new KnownBlockStorage(database, blocks, indexes);
            lookup = new StoredBlockIndexLookup(indexes);
            genesis = BlockIndexFactory.createGenesis(GenesisBlockFactory.create(PARAMETERS).header());
            state = new ChainInitializer(database, PARAMETERS).initialize();
            var transitions = new RocksDbChainTransitionStorage(database, utxos, undos, indexes, tips);
            executor = new ChainReorganizationExecutor(blocks, undos, utxos,
                    new ChainTransitionManager(state, transitions), PARAMETERS, lookup);
            processor = processor(known);
        }

        BlockProcessor processor(KnownBlockStorage storage) {
            return new BlockProcessor(state, lookup, storage, executor, PARAMETERS, () -> TIME + 1000);
        }

        void assertTip(Hash256 expected) {
            assertEquals(expected, state.activeTip().hash());
            assertEquals(expected, tips.loadActiveTipHash().orElseThrow());
        }

        @Override
        public void close() {
            database.close();
        }
    }
}
