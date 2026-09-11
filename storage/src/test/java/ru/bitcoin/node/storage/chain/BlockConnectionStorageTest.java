package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockConnectionStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldAtomicallyCommitConnectedBlock() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        StoredBlockIndex blockIndex =
                blockIndex();

        OutPoint spentOutPoint =
                outPoint(
                        'a',
                        0
                );

        OutPoint createdOutPoint =
                outPoint(
                        'b',
                        0
                );

        Block block =
                new Block(
                        blockIndex.header(),
                        List.of()
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

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(
                                spentOutPoint
                        ),
                        List.of(
                                new CreatedUtxo(
                                        createdOutPoint,
                                        createdUtxo
                                )
                        )
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

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            BlockConnectionStorage connectionStorage =
                    new BlockConnectionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            blockStore,
                            chainStateStore
                    );

            /*
             * Исходный UTXO существует до блока.
             */
            utxoStore.save(
                    spentOutPoint,
                    spentUtxo
            );

            connectionStorage.commitConnectedBlock(
                    block,
                    blockIndex,
                    changes,
                    undoData
            );

            assertEquals(
                    block,
                    blockStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            /*
             * Потраченный output исчез.
             */
            assertTrue(
                    utxoStore.find(spentOutPoint)
                            .isEmpty()
            );

            /*
             * Новый output появился.
             */
            assertEquals(
                    createdUtxo,
                    utxoStore.find(createdOutPoint)
                            .orElseThrow()
            );

            /*
             * Undo сохранён.
             */
            assertEquals(
                    undoData,
                    undoStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            /*
             * BlockIndex сохранён.
             */
            assertEquals(
                    blockIndex,
                    blockIndexStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            /*
             * Новый блок стал active tip.
             */
            assertEquals(
                    blockIndex.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRemoveOutputCreatedAndSpentInsideSameBlock() {

        Path databasePath =
                tempDirectory.resolve("same-block-spend");

        StoredBlockIndex blockIndex =
                blockIndex();

        OutPoint intermediateOutPoint =
                outPoint(
                        'c',
                        0
                );

        StoredUtxo intermediateUtxo =
                new StoredUtxo(
                        25_000L,
                        new byte[]{0x51},
                        101L,
                        false
                );

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(
                                intermediateOutPoint
                        ),
                        List.of(
                                new CreatedUtxo(
                                        intermediateOutPoint,
                                        intermediateUtxo
                                )
                        )
                );

        Block block =
                new Block(
                        blockIndex.header(),
                        List.of()
                );

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of()
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

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            BlockConnectionStorage connectionStorage =
                    new BlockConnectionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            blockStore,
                            chainStateStore
                    );

            connectionStorage.commitConnectedBlock(
                    block,
                    blockIndex,
                    changes,
                    undoData
            );

            assertEquals(
                    block,
                    blockStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            /*
             * PUT был раньше DELETE,
             * поэтому промежуточного UTXO
             * в итоговом UTXO-set нет.
             */
            assertTrue(
                    utxoStore.find(
                                    intermediateOutPoint
                            )
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldPersistConnectedBlockAfterReopen() {

        Path databasePath =
                tempDirectory.resolve("reopen");

        StoredBlockIndex blockIndex =
                blockIndex();

        OutPoint createdOutPoint =
                outPoint(
                        'd',
                        0
                );

        StoredUtxo createdUtxo =
                new StoredUtxo(
                        15_000L,
                        new byte[]{0x51},
                        101L,
                        false
                );

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(),
                        List.of(
                                new CreatedUtxo(
                                        createdOutPoint,
                                        createdUtxo
                                )
                        )
                );

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of()
                );

        Block block =
                new Block(
                        blockIndex.header(),
                        List.of()
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

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            BlockConnectionStorage connectionStorage =
                    new BlockConnectionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            blockStore,
                            chainStateStore
                    );

            connectionStorage.commitConnectedBlock(
                    block,
                    blockIndex,
                    changes,
                    undoData
            );

            assertEquals(
                    block,
                    blockStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );
        }

        /*
         * Проверяем durable state после reopen.
         */
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

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            assertEquals(
                    block,
                    blockStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            assertEquals(
                    createdUtxo,
                    utxoStore.find(createdOutPoint)
                            .orElseThrow()
            );

            assertEquals(
                    undoData,
                    undoStore.find(
                                    blockIndex.hash()
                            )
                            .orElseThrow()
            );

            assertEquals(
                    blockIndex,
                    blockIndexStore.find(
                                    blockIndex.hash()
                            )
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

    private static StoredBlockIndex blockIndex() {

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

        return new StoredBlockIndex(
                header.hash(),
                header,
                0L,
                header.previousBlockHash(),
                BigInteger.valueOf(
                        4_295_032_833L
                )
        );
    }

    private static OutPoint outPoint(
            char hexCharacter,
            long index
    ) {

        return new OutPoint(
                Hash256.fromDisplayHex(
                        String.valueOf(
                                hexCharacter
                        ).repeat(64)
                ),
                new UInt32(index)
        );
    }
}