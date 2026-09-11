package ru.bitcoin.node.chain.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbChainTransitionStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldAtomicallyCommitWholeReorganization() {

        Path databasePath =
                tempDirectory.resolve("reorg");

        StoredBlockIndex oldTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L
                );

        StoredBlockIndex newTip =
                blockIndex(
                        'b',
                        oldTip.hash(),
                        101L
                );

        OutPoint oldChainOutput =
                outPoint('c', 0);

        StoredUtxo oldChainUtxo =
                utxo(50_000L);

        OutPoint newChainOutput =
                outPoint('d', 0);

        StoredUtxo newChainUtxo =
                utxo(40_000L);

        UtxoChanges utxoChanges =
                new UtxoChanges(
                        List.of(
                                oldChainOutput
                        ),
                        List.of(
                                new CreatedUtxo(
                                        newChainOutput,
                                        newChainUtxo
                                )
                        )
                );

        BlockUndoData newUndo =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                oldChainUtxo
                                        )
                                )
                        )
                );

        Map<Hash256, BlockUndoData> connectedUndo =
                new LinkedHashMap<>();

        connectedUndo.put(
                newTip.hash(),
                newUndo
        );

        BlockReorganizationChanges changes =
                new BlockReorganizationChanges(
                        utxoChanges,
                        connectedUndo
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

            blockIndexStore.save(oldTip);
            blockIndexStore.save(newTip);

            chainStateStore.saveActiveTipHash(
                    oldTip.hash()
            );

            utxoStore.save(
                    oldChainOutput,
                    oldChainUtxo
            );

            RocksDbChainTransitionStorage storage =
                    new RocksDbChainTransitionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            storage.commit(
                    oldTip.hash(),
                    newTip.hash(),
                    changes
            );

            assertTrue(
                    utxoStore.find(oldChainOutput)
                            .isEmpty()
            );

            assertEquals(
                    newChainUtxo,
                    utxoStore.find(newChainOutput)
                            .orElseThrow()
            );

            assertEquals(
                    newUndo,
                    undoStore.find(newTip.hash())
                            .orElseThrow()
            );

            assertEquals(
                    newTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            /*
             * Старый BlockIndex остаётся.
             */
            assertEquals(
                    oldTip,
                    blockIndexStore
                            .find(oldTip.hash())
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRejectStaleTransitionWithoutChangingDatabase() {

        Path databasePath =
                tempDirectory.resolve("stale");

        StoredBlockIndex actualTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L
                );

        StoredBlockIndex newTip =
                blockIndex(
                        'b',
                        actualTip.hash(),
                        101L
                );

        Hash256 staleExpectedTip =
                Hash256.fromDisplayHex(
                        "cc".repeat(32)
                );

        OutPoint newOutput =
                outPoint('d', 0);

        StoredUtxo newUtxo =
                utxo(10_000L);

        BlockReorganizationChanges changes =
                new BlockReorganizationChanges(
                        new UtxoChanges(
                                List.of(),
                                List.of(
                                        new CreatedUtxo(
                                                newOutput,
                                                newUtxo
                                        )
                                )
                        ),
                        Map.of()
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

            blockIndexStore.save(actualTip);
            blockIndexStore.save(newTip);

            chainStateStore.saveActiveTipHash(
                    actualTip.hash()
            );

            RocksDbChainTransitionStorage storage =
                    new RocksDbChainTransitionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            storage.commit(
                                    staleExpectedTip,
                                    newTip.hash(),
                                    changes
                            )
            );

            assertTrue(
                    utxoStore.find(newOutput)
                            .isEmpty()
            );

            assertEquals(
                    actualTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRejectUnknownNewTip() {

        Path databasePath =
                tempDirectory.resolve("unknown-tip");

        StoredBlockIndex oldTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L
                );

        Hash256 unknownNewTip =
                Hash256.fromDisplayHex(
                        "ff".repeat(32)
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

            blockIndexStore.save(oldTip);

            chainStateStore.saveActiveTipHash(
                    oldTip.hash()
            );

            RocksDbChainTransitionStorage storage =
                    new RocksDbChainTransitionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            storage.commit(
                                    oldTip.hash(),
                                    unknownNewTip,
                                    new BlockReorganizationChanges(
                                            new UtxoChanges(
                                                    List.of(),
                                                    List.of()
                                            ),
                                            Map.of()
                                    )
                            )
            );

            assertEquals(
                    oldTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldPersistWholeTransitionAfterReopen() {

        Path databasePath =
                tempDirectory.resolve("reopen");

        StoredBlockIndex oldTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L
                );

        StoredBlockIndex newTip =
                blockIndex(
                        'b',
                        oldTip.hash(),
                        101L
                );

        OutPoint oldOutput =
                outPoint('c', 0);

        StoredUtxo oldUtxo =
                utxo(50_000L);

        OutPoint newOutput =
                outPoint('d', 0);

        StoredUtxo newUtxo =
                utxo(40_000L);

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of()
                );

        BlockReorganizationChanges changes =
                new BlockReorganizationChanges(
                        new UtxoChanges(
                                List.of(
                                        oldOutput
                                ),
                                List.of(
                                        new CreatedUtxo(
                                                newOutput,
                                                newUtxo
                                        )
                                )
                        ),
                        Map.of(
                                newTip.hash(),
                                undoData
                        )
                );

        /*
         * Первая сессия.
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

            blockIndexStore.save(oldTip);
            blockIndexStore.save(newTip);

            utxoStore.save(
                    oldOutput,
                    oldUtxo
            );

            chainStateStore.saveActiveTipHash(
                    oldTip.hash()
            );

            RocksDbChainTransitionStorage storage =
                    new RocksDbChainTransitionStorage(
                            database,
                            utxoStore,
                            undoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            storage.commit(
                    oldTip.hash(),
                    newTip.hash(),
                    changes
            );
        }

        /*
         * Полностью новая RocksDB session.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbUndoStore undoStore =
                    new RocksDbUndoStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            assertTrue(
                    utxoStore.find(oldOutput)
                            .isEmpty()
            );

            assertEquals(
                    newUtxo,
                    utxoStore.find(newOutput)
                            .orElseThrow()
            );

            assertEquals(
                    undoData,
                    undoStore.find(newTip.hash())
                            .orElseThrow()
            );

            assertEquals(
                    newTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    /*
     * Helpers должны находиться здесь:
     * внутри класса, но СНАРУЖИ всех @Test методов.
     */

    private static StoredBlockIndex blockIndex(
            char merkleCharacter,
            Hash256 previousHash,
            long height
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

        return new StoredBlockIndex(
                header.hash(),
                header,
                height,
                previousHash,
                BigInteger.valueOf(
                        height + 1
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

    private static StoredUtxo utxo(
            long amount
    ) {

        return new StoredUtxo(
                amount,
                new byte[]{0x51},
                100L,
                false
        );
    }
}