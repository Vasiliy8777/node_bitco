package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
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
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainReorganizationExecutorSuccessTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldExecuteSuccessfulChainTransition() {

        Path databasePath =
                tempDirectory.resolve("successful-transition");

        /*
         * Старый active tip.
         */
        BlockIndex ancestor90 =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        90L,
                        BigInteger.valueOf(900)
                );

        BlockIndex ancestor91 =
                blockIndex(
                        'b',
                        ancestor90.hash(),
                        91L,
                        BigInteger.valueOf(910)
                );

        BlockIndex ancestor92 =
                blockIndex(
                        'c',
                        ancestor91.hash(),
                        92L,
                        BigInteger.valueOf(920)
                );

        BlockIndex ancestor93 =
                blockIndex(
                        'd',
                        ancestor92.hash(),
                        93L,
                        BigInteger.valueOf(930)
                );

        BlockIndex ancestor94 =
                blockIndex(
                        'e',
                        ancestor93.hash(),
                        94L,
                        BigInteger.valueOf(940)
                );

        BlockIndex ancestor95 =
                blockIndex(
                        'f',
                        ancestor94.hash(),
                        95L,
                        BigInteger.valueOf(950)
                );

        BlockIndex ancestor96 =
                blockIndex(
                        '1',
                        ancestor95.hash(),
                        96L,
                        BigInteger.valueOf(960)
                );

        BlockIndex ancestor97 =
                blockIndex(
                        '2',
                        ancestor96.hash(),
                        97L,
                        BigInteger.valueOf(970)
                );

        BlockIndex ancestor98 =
                blockIndex(
                        '3',
                        ancestor97.hash(),
                        98L,
                        BigInteger.valueOf(980)
                );

        BlockIndex ancestor99 =
                blockIndex(
                        '4',
                        ancestor98.hash(),
                        99L,
                        BigInteger.valueOf(990)
                );

        BlockIndex oldTip =
                blockIndex(
                        '5',
                        ancestor99.hash(),
                        100L,
                        BigInteger.valueOf(1000)
                );

        /*
         * Новый блок будет ребёнком oldTip.
         *
         * Сам BlockIndex ниже создадим уже после
         * формирования Block, чтобы hash точно совпадал.
         */

        /*
         * Coinbase transaction.
         */
        Transaction coinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                0x01,
                                                0x65
                                        },
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
        Hash256 merkleRoot =
                MerkleTree.calculateRoot(
                        List.of(
                                coinbase.txId()
                        )
                );
        BlockHeader newHeader =
                new BlockHeader(
                        1,
                        oldTip.hash(),
                        merkleRoot,
                        new UInt32(1_700_000_101L),
                        new UInt32(0x207FFFFFL),
                        new UInt32(2)
                );

        Block newBlock =
                new Block(
                        newHeader,
                        List.of(coinbase)
                );

        BlockIndex newTip =
                new BlockIndex(
                        newBlock.hash(),
                        newHeader,
                        101L,
                        oldTip.hash(),
                        BigInteger.valueOf(2000)
                );

        ChainState chainState =
                new ChainState(oldTip);

        ReorganizationPlan plan =
                new ReorganizationPlan(
                        oldTip,
                        List.of(),
                        List.of(newTip)
                );

        ChainUpdate update =
                new ChainUpdate(
                        oldTip,
                        newTip,
                        plan
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

            blockIndexStore.save(toStored(ancestor90));
            blockIndexStore.save(toStored(ancestor91));
            blockIndexStore.save(toStored(ancestor92));
            blockIndexStore.save(toStored(ancestor93));
            blockIndexStore.save(toStored(ancestor94));
            blockIndexStore.save(toStored(ancestor95));
            blockIndexStore.save(toStored(ancestor96));
            blockIndexStore.save(toStored(ancestor97));
            blockIndexStore.save(toStored(ancestor98));
            blockIndexStore.save(toStored(ancestor99));
            blockIndexStore.save(toStored(oldTip));

            /*
             * Новый блок уже известен ноде:
             *
             * body + block index persisted,
             * но ещё НЕ active.
             */
            blockStore.save(newBlock);

            blockIndexStore.save(
                    toStored(newTip)
            );

            /*
             * Текущий active tip на диске.
             */
            chainStateStore.saveActiveTipHash(
                    oldTip.hash()
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

            StoredBlockIndexLookup blockIndexLookup =
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
                            blockIndexLookup
                    );

            /*
             * До перехода coinbase output ещё
             * не существует в UTXO.
             */
            OutPoint coinbaseOutPoint =
                    new OutPoint(
                            coinbase.txId(),
                            new UInt32(0)
                    );

            assertTrue(
                    utxoStore
                            .find(coinbaseOutPoint)
                            .isEmpty()
            );

            /*
             * Выполняем полный transition.
             */
            executor.execute(update);

            /*
             * RAM tip переключён.
             */
            assertEquals(
                    newTip,
                    chainState.activeTip()
            );

            /*
             * Disk tip переключён.
             */
            assertEquals(
                    newTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            /*
             * Coinbase output появился в UTXO.
             */
            StoredUtxo storedCoinbase =
                    utxoStore
                            .find(coinbaseOutPoint)
                            .orElseThrow();

            assertEquals(
                    5_000L,
                    storedCoinbase.amount()
            );

            assertEquals(
                    101L,
                    storedCoinbase.height()
            );

            assertTrue(
                    storedCoinbase.coinbase()
            );

            assertArrayEquals(
                    new byte[]{0x51},
                    storedCoinbase.scriptPubKey()
            );

            /*
             * Undo нового блока тоже должен быть записан.
             *
             * Для блока только с coinbase:
             * transactions undo = empty list.
             */
            BlockUndoData undoData =
                    undoStore
                            .find(newTip.hash())
                            .orElseThrow();

            assertTrue(
                    undoData
                            .transactions()
                            .isEmpty()
            );

            /*
             * Body нового блока остаётся доступен.
             */
            assertEquals(
                    newBlock,
                    blockStore
                            .find(newTip.hash())
                            .orElseThrow()
            );

            /*
             * BlockIndex нового tip тоже остаётся.
             */
            assertTrue(
                    blockIndexStore
                            .find(newTip.hash())
                            .isPresent()
            );
        }
    }

    private static BlockIndex blockIndex(
            char merkleCharacter,
            Hash256 previousHash,
            long height,
            BigInteger chainWork
    ) {
        BlockHeader header =
                new BlockHeader(
                        1,
                        previousHash,
                        Hash256.fromDisplayHex(
                                String.valueOf(
                                        merkleCharacter
                                ).repeat(64)
                        ),
                        new UInt32(
                                1_700_000_000L + height
                        ),
                        new UInt32(
                                0x207FFFFFL
                        ),
                        new UInt32(
                                height + 1
                        )
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousHash,
                chainWork
        );
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
}