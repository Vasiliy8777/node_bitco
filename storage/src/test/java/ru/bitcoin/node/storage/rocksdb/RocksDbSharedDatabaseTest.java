package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocksDbSharedDatabaseTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldStoreBlockIndexAndActiveTipInSameDatabase() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        StoredBlockIndex blockIndex =
                storedGenesis();

        /*
         * Первый запуск.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            blockIndexStore.save(
                    blockIndex
            );

            chainStateStore.saveActiveTipHash(
                    blockIndex.hash()
            );

            assertTrue(
                    blockIndexStore
                            .find(blockIndex.hash())
                            .isPresent()
            );

            assertEquals(
                    blockIndex.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }

        /*
         * Полностью закрываем RocksDB и открываем снова.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            StoredBlockIndex restored =
                    blockIndexStore
                            .find(blockIndex.hash())
                            .orElseThrow();

            assertEquals(
                    blockIndex,
                    restored
            );

            assertEquals(
                    blockIndex.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldNotMixBlockIndexAndChainStateKeys() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        StoredBlockIndex blockIndex =
                storedGenesis();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            blockIndexStore.save(
                    blockIndex
            );

            /*
             * Active tip пока не сохраняли.
             *
             * Наличие BlockIndex в той же RocksDB
             * не должно восприниматься как ChainState.
             */
            assertTrue(
                    chainStateStore
                            .loadActiveTipHash()
                            .isEmpty()
            );

            /*
             * Теперь сохраняем active tip.
             */
            chainStateStore.saveActiveTipHash(
                    blockIndex.hash()
            );

            /*
             * Запись ChainState не должна повредить
             * BlockIndex.
             */
            assertEquals(
                    blockIndex,
                    blockIndexStore
                            .find(blockIndex.hash())
                            .orElseThrow()
            );
        }
    }

    private static StoredBlockIndex storedGenesis() {

        BlockHeader header =
                genesisHeader();

        return new StoredBlockIndex(
                header.hash(),
                header,
                0,
                header.previousBlockHash(),
                BigInteger.valueOf(
                        4295032833L
                )
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "00000000000000000000000000000000" +
                                "00000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87" +
                                "f618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(
                        1231006505L
                ),
                new UInt32(
                        0x1D00FFFFL
                ),
                new UInt32(
                        2083236893L
                )
        );
    }
    @Test
    void shouldStoreUtxoTogetherWithBlockIndexAndChainState() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        StoredBlockIndex blockIndex =
                storedGenesis();

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        ),
                        new UInt32(0)
                );

        StoredUtxo utxo =
                new StoredUtxo(
                        25_000L,
                        new byte[]{0x51},
                        1L,
                        false
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            blockIndexStore.save(
                    blockIndex
            );

            chainStateStore.saveActiveTipHash(
                    blockIndex.hash()
            );

            utxoStore.save(
                    outPoint,
                    utxo
            );

            assertEquals(
                    blockIndex,
                    blockIndexStore
                            .find(blockIndex.hash())
                            .orElseThrow()
            );

            assertEquals(
                    blockIndex.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    utxo,
                    utxoStore
                            .find(outPoint)
                            .orElseThrow()
            );
        }
    }
    @Test
    void shouldStoreUndoTogetherWithOtherData() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Hash256 blockHash =
                Hash256.fromDisplayHex(
                        "cccccccccccccccccccccccccccccccc" +
                                "cccccccccccccccccccccccccccccccc"
                );

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                new StoredUtxo(
                                                        15_000L,
                                                        new byte[]{0x51},
                                                        500L,
                                                        false
                                                )
                                        )
                                )
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore undoStore =
                    new RocksDbUndoStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            undoStore.save(
                    blockHash,
                    undoData
            );

            chainStateStore.saveActiveTipHash(
                    blockHash
            );

            assertEquals(
                    undoData,
                    undoStore.find(blockHash)
                            .orElseThrow()
            );

            assertEquals(
                    blockHash,
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }
}