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

import static org.junit.jupiter.api.Assertions.*;

class RocksDbStoreBatchTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldAtomicallyApplyChangesAcrossStores() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00000000000000000000000000000000" +
                                        "00000000000000000000000000000000"
                        ),
                        Hash256.fromDisplayHex(
                                "4a5e1e4baab89f3a32518a88c31bc87" +
                                        "f618f76673e2cc77ab2127b7afdeda33b"
                        ),
                        new UInt32(1231006505L),
                        new UInt32(0x1D00FFFFL),
                        new UInt32(2083236893L)
                );

        StoredBlockIndex blockIndex =
                new StoredBlockIndex(
                        header.hash(),
                        header,
                        0L,
                        header.previousBlockHash(),
                        BigInteger.valueOf(
                                4_295_032_833L
                        )
                );

        OutPoint spentOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        ),
                        new UInt32(0)
                );

        OutPoint createdOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        ),
                        new UInt32(1)
                );

        StoredUtxo spentUtxo =
                new StoredUtxo(
                        50_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        StoredUtxo createdUtxo =
                new StoredUtxo(
                        40_000L,
                        new byte[]{0x52},
                        101L,
                        false
                );

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                spentUtxo
                                        )
                                )
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbUndoStore undoStore =
                    new RocksDbUndoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            /*
             * Представим, что spentOutPoint существовал
             * до подключения нового блока.
             */
            utxoStore.save(
                    spentOutPoint,
                    spentUtxo
            );

            assertEquals(
                    spentUtxo,
                    utxoStore.find(spentOutPoint)
                            .orElseThrow()
            );

            assertTrue(
                    utxoStore.find(createdOutPoint)
                            .isEmpty()
            );

            assertTrue(
                    undoStore.find(blockIndex.hash())
                            .isEmpty()
            );

            assertTrue(
                    blockIndexStore.find(blockIndex.hash())
                            .isEmpty()
            );

            assertTrue(
                    chainStateStore.loadActiveTipHash()
                            .isEmpty()
            );

            try (RocksDbWriteBatch batch =
                         new RocksDbWriteBatch()) {

                /*
                 * Тратим старый UTXO.
                 */
                utxoStore.delete(
                        batch,
                        spentOutPoint
                );

                /*
                 * Создаём новый UTXO.
                 */
                utxoStore.save(
                        batch,
                        createdOutPoint,
                        createdUtxo
                );

                /*
                 * Сохраняем undo.
                 */
                undoStore.save(
                        batch,
                        blockIndex.hash(),
                        undoData
                );

                /*
                 * Сохраняем BlockIndex.
                 */
                blockIndexStore.save(
                        batch,
                        blockIndex
                );

                /*
                 * И только в этом же batch меняем active tip.
                 */
                chainStateStore.saveActiveTipHash(
                        batch,
                        blockIndex.hash()
                );

                /*
                 * Критически важная проверка:
                 * до database.write(batch) база всё ещё
                 * находится в старом состоянии.
                 */

                assertEquals(
                        spentUtxo,
                        utxoStore.find(spentOutPoint)
                                .orElseThrow()
                );

                assertTrue(
                        utxoStore.find(createdOutPoint)
                                .isEmpty()
                );

                assertTrue(
                        undoStore.find(blockIndex.hash())
                                .isEmpty()
                );

                assertTrue(
                        blockIndexStore.find(blockIndex.hash())
                                .isEmpty()
                );

                assertTrue(
                        chainStateStore.loadActiveTipHash()
                                .isEmpty()
                );

                /*
                 * Единственная точка commit.
                 */
                database.write(
                        batch
                );
            }

            /*
             * После commit всё состояние поменялось.
             */

            assertTrue(
                    utxoStore.find(spentOutPoint)
                            .isEmpty()
            );

            assertEquals(
                    createdUtxo,
                    utxoStore.find(createdOutPoint)
                            .orElseThrow()
            );

            assertEquals(
                    undoData,
                    undoStore.find(blockIndex.hash())
                            .orElseThrow()
            );

            assertEquals(
                    blockIndex,
                    blockIndexStore.find(blockIndex.hash())
                            .orElseThrow()
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
    void shouldNotApplyBatchWithoutDatabaseWrite() {

        Path databasePath =
                tempDirectory.resolve("uncommitted");

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "cccccccccccccccccccccccccccccccc" +
                                        "cccccccccccccccccccccccccccccccc"
                        ),
                        new UInt32(0)
                );

        StoredUtxo utxo =
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        1L,
                        false
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            try (RocksDbWriteBatch batch =
                         new RocksDbWriteBatch()) {

                utxoStore.save(
                        batch,
                        outPoint,
                        utxo
                );

                /*
                 * database.write(batch) специально
                 * НЕ вызываем.
                 */
            }

            assertTrue(
                    utxoStore.find(outPoint)
                            .isEmpty()
            );
        }
    }
}