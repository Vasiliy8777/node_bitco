package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.SequenceLocks;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChainReorganizationBip68Test {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldUseCandidateBranchMtpForBip68DuringReorganization() {

        Path databasePath =
                tempDirectory.resolve(
                        "candidate-branch-mtp"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            RocksDbUndoStore undoStore =
                    new RocksDbUndoStore(database);

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            /*
             * -------------------------------------------------
             * Общая история до height=90.
             * -------------------------------------------------
             */
            TestBranch common =
                    createBranch(
                            null,
                            80L,
                            90L,
                            1_000_000L,
                            600L,
                            BigInteger.ONE
                    );

            BlockIndex commonAncestor =
                    common.atHeight(90L);

            /*
             * -------------------------------------------------
             * ACTIVE branch A.
             *
             * timestamps очень высокие.
             *
             * Если BIP68 ошибочно возьмёт MTP отсюда,
             * транзакция candidate-ветки окажется valid.
             * -------------------------------------------------
             */
            TestBranch active =
                    createBranch(
                            commonAncestor,
                            91L,
                            100L,
                            2_000_000L,
                            10_000L,
                            BigInteger.valueOf(1_000L)
                    );

            BlockIndex oldTip =
                    active.atHeight(100L);

            /*
             * -------------------------------------------------
             * CANDIDATE branch B.
             *
             * timestamps растут медленно.
             *
             * Правильный candidate MTP будет существенно ниже.
             * -------------------------------------------------
             */
            TestBranch candidate =
                    createBranch(
                            commonAncestor,
                            91L,
                            100L,
                            1_100_000L,
                            100L,
                            BigInteger.valueOf(2_000L)
                    );

            BlockIndex candidateParent =
                    candidate.atHeight(100L);

            /*
             * UTXO считаем созданным на candidate branch
             * на height=95.
             */
            OutPoint previousOutput =
                    new OutPoint(
                            Hash256.fromDisplayHex(
                                    "77".repeat(32)
                            ),
                            new UInt32(0)
                    );

            utxoStore.save(
                    previousOutput,
                    new StoredUtxo(
                            10_000L,
                            new byte[]{0x51},
                            95L,
                            false
                    )
            );

            /*
             * Time-based BIP68:
             *
             * TYPE_FLAG | 186
             *
             * relative time:
             *
             * 186 << 9 = 95_232 seconds.
             *
             * Значение специально выбрано так, чтобы:
             *
             * candidate branch B -> INVALID
             * active branch A    -> VALID
             */
            long sequence =
                    SequenceLocks
                            .SEQUENCE_LOCKTIME_TYPE_FLAG
                            | 186L;

            Transaction spendingTransaction =
                    new Transaction(
                            2,
                            List.of(
                                    new TxIn(
                                            previousOutput,
                                            new byte[0],
                                            new UInt32(
                                                    sequence
                                            )
                                    )
                            ),
                            List.of(
                                    new TxOut(
                                            9_000L,
                                            new byte[]{0x51}
                                    )
                            ),
                            new UInt32(0)
                    );

            Transaction coinbase =
                    coinbase(101L);

            Block candidateBlock =
                    block(
                            candidateParent.hash(),
                            1_200_000L,
                            101L,
                            List.of(
                                    coinbase,
                                    spendingTransaction
                            )
                    );

            BlockIndex newTip =
                    new BlockIndex(
                            candidateBlock
                                    .header()
                                    .hash(),
                            candidateBlock.header(),
                            101L,
                            candidateParent.hash(),
                            BigInteger.valueOf(
                                    10_000L
                            )
                    );

            /*
             * -------------------------------------------------
             * Все BlockIndex обеих веток должны быть известны
             * lookup.
             * -------------------------------------------------
             */
            saveIndexes(
                    blockIndexStore,
                    common
            );

            saveIndexes(
                    blockIndexStore,
                    active
            );

            saveIndexes(
                    blockIndexStore,
                    candidate
            );

            blockIndexStore.save(
                    toStored(newTip)
            );

            /*
             * Body нужен только подключаемому B101.
             */
            blockStore.save(
                    candidateBlock
            );

            /*
             * Active tip на диске — ветка A.
             */
            chainStateStore.saveActiveTipHash(
                    oldTip.hash()
            );

            ChainState chainState =
                    new ChainState(
                            oldTip
                    );

            RocksDbChainTransitionStorage transitionStorage =
                    new RocksDbChainTransitionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            ChainTransitionManager transitionManager =
                    new ChainTransitionManager(
                            chainState,
                            transitionStorage
                    );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            blockIndexStore
                    );

            ChainReorganizationExecutor executor =
                    new ChainReorganizationExecutor(
                            blockStore,
                            undoStore,
                            utxoStore,
                            transitionManager,
                            NetworkParametersRegistry.regtest(),
                            lookup
                    );

            /*
             * Для этого теста disconnect не нужен:
             *
             * мы проверяем именно то, какую ancestry
             * использует candidate B101 при BIP68.
             */
            ReorganizationPlan plan =
                    new ReorganizationPlan(
                            commonAncestor,
                            List.of(),
                            List.of(
                                    newTip
                            )
                    );

            ChainUpdate update =
                    new ChainUpdate(
                            oldTip,
                            newTip,
                            plan
                    );

            long candidatePreviousMtp =
                    MedianTimePast.calculate(
                            candidateParent,
                            lookup
                    );

            long activePreviousMtp =
                    MedianTimePast.calculate(
                            oldTip,
                            lookup
                    );

            AncestorMedianTimePastResolver candidateResolver =
                    new AncestorMedianTimePastResolver(
                            newTip,
                            lookup
                    );

            long coinPreviousMtp =
                    candidateResolver.resolveForCoinHeight(
                            95L
                    );

            long minimumTime =
                    coinPreviousMtp
                            + (186L
                            << SequenceLocks
                            .SEQUENCE_LOCKTIME_GRANULARITY)
                            - 1L;

            assertEquals(
                    1_005_400L,
                    coinPreviousMtp
            );

            assertEquals(
                    1_100_400L,
                    candidatePreviousMtp
            );

            assertEquals(
                    2_040_000L,
                    activePreviousMtp
            );

            assertEquals(
                    1_100_631L,
                    minimumTime
            );

            /*
             * Главная предпосылка regression-теста:
             *
             * candidate branch B -> INVALID
             * active branch A    -> VALID
             */
            assertTrue(
                    minimumTime >= candidatePreviousMtp,
                    "Test setup error: transaction must be invalid "
                            + "against candidate branch MTP"
            );

            assertTrue(
                    minimumTime < activePreviousMtp,
                    "Test setup error: transaction must be valid "
                            + "against active branch MTP"
            );

            /*
             * При ПРАВИЛЬНОМ использовании candidate branch
             * relative time lock ещё не выполнен.
             */
            assertThrows(
                    TransactionValidationException.class,
                    () -> executor.execute(update)
            );

            /*
             * Consensus validation завершилась ошибкой
             * ДО persistent transition.
             *
             * RAM active tip не должен измениться.
             */
            assertEquals(
                    oldTip,
                    chainState.activeTip()
            );

            /*
             * Disk active tip тоже должен остаться oldTip.
             */
            assertEquals(
                    oldTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            /*
             * И исходный UTXO не должен быть потрачен,
             * потому что commit вообще не состоялся.
             */
            StoredUtxo stillStored =
                    utxoStore
                            .find(previousOutput)
                            .orElseThrow();

            assertEquals(
                    10_000L,
                    stillStored.amount()
            );
        }
    }

    private static TestBranch createBranch(
            BlockIndex parent,
            long fromHeight,
            long toHeight,
            long firstTimestamp,
            long timestampStep,
            BigInteger firstChainWork
    ) {

        Map<Long, BlockIndex> indexes =
                new HashMap<>();

        Hash256 previousHash;

        if (parent == null) {
            previousHash =
                    Hash256.fromDisplayHex(
                            "00".repeat(32)
                    );
        } else {
            previousHash =
                    parent.hash();
        }

        BigInteger chainWork =
                firstChainWork;

        for (long height = fromHeight;
             height <= toHeight;
             height++) {

            long timestamp =
                    firstTimestamp
                            + (height - fromHeight)
                            * timestampStep;

            BlockHeader header =
                    new BlockHeader(
                            1,
                            previousHash,
                            syntheticMerkleRoot(
                                    height,
                                    firstTimestamp
                            ),
                            new UInt32(
                                    timestamp
                            ),
                            new UInt32(
                                    0x207FFFFFL
                            ),
                            new UInt32(
                                    height
                            )
                    );

            BlockIndex index =
                    new BlockIndex(
                            header.hash(),
                            header,
                            height,
                            previousHash,
                            chainWork
                    );

            indexes.put(
                    height,
                    index
            );

            previousHash =
                    index.hash();

            chainWork =
                    chainWork.add(
                            BigInteger.ONE
                    );
        }

        return new TestBranch(
                indexes
        );
    }

    private static Block block(
            Hash256 previousBlockHash,
            long timestamp,
            long height,
            List<Transaction> transactions
    ) {

        Hash256 merkleRoot =
                MerkleTree.calculateRoot(
                        transactions.stream()
                                .map(Transaction::txId)
                                .toList()
                );

        BlockHeader header =
                new BlockHeader(
                        1,
                        previousBlockHash,
                        merkleRoot,
                        new UInt32(timestamp),
                        new UInt32(
                                0x207FFFFFL
                        ),
                        new UInt32(
                                height
                        )
                );

        return new Block(
                header,
                transactions
        );
    }

    private static Transaction coinbase(
            long blockHeight
    ) {

        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                bip34HeightScriptSig(
                                        blockHeight
                                ),
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                5_000L,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }

    private static byte[] bip34HeightScriptSig(
            long blockHeight
    ) {

        if (blockHeight <= 0) {
            throw new IllegalArgumentException(
                    "blockHeight must be positive"
            );
        }

        if (blockHeight <= 16) {
            return new byte[]{
                    (byte) (
                            0x51
                                    + blockHeight
                                    - 1
                    ),
                    0x00
            };
        }

        List<Byte> bytes =
                new ArrayList<>();

        long value =
                blockHeight;

        while (value != 0) {

            bytes.add(
                    (byte) (
                            value & 0xFF
                    )
            );

            value >>>= 8;
        }

        if ((bytes.get(
                bytes.size() - 1
        ) & 0x80) != 0) {

            bytes.add(
                    (byte) 0x00
            );
        }

        byte[] scriptSig =
                new byte[
                        1 + bytes.size()
                        ];

        scriptSig[0] =
                (byte) bytes.size();

        for (int i = 0;
             i < bytes.size();
             i++) {

            scriptSig[i + 1] =
                    bytes.get(i);
        }

        return scriptSig;
    }

    private static Hash256 syntheticMerkleRoot(
            long height,
            long branchMarker
    ) {

        /*
         * Только для получения уникальных
         * synthetic BlockHeader hashes.
         */
        String hex =
                String.format(
                        "%064x",
                        height
                                + branchMarker
                );

        return Hash256.fromDisplayHex(
                hex
        );
    }

    private static void saveIndexes(
            RocksDbBlockIndexStore store,
            TestBranch branch
    ) {

        for (BlockIndex index
                : branch.indexes.values()) {

            store.save(
                    toStored(index)
            );
        }
    }

    private static StoredBlockIndex toStored(
            BlockIndex index
    ) {

        return new StoredBlockIndex(
                index.hash(),
                index.header(),
                index.height(),
                index.previousBlockHash(),
                index.chainWork()
        );
    }

    private static final class TestBranch {

        private final Map<Long, BlockIndex>
                indexes;

        private TestBranch(
                Map<Long, BlockIndex> indexes
        ) {
            this.indexes =
                    Map.copyOf(indexes);
        }

        private BlockIndex atHeight(
                long height
        ) {

            BlockIndex index =
                    indexes.get(
                            height
                    );

            if (index == null) {
                throw new IllegalStateException(
                        "Missing test BlockIndex at height "
                                + height
                );
            }

            return index;
        }
    }
}