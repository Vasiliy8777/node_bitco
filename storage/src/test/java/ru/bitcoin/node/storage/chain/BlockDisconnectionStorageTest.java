package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockDisconnectionStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldAtomicallyDisconnectActiveTip() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        StoredBlockIndex parent =
                parentBlockIndex();

        StoredBlockIndex child =
                childBlockIndex(parent);

        /*
         * Этот UTXO существовал до child,
         * но child его потратил.
         */
        OutPoint previouslySpent =
                outPoint(
                        'a',
                        0
                );

        StoredUtxo restoredUtxo =
                new StoredUtxo(
                        50_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        /*
         * Этот UTXO был создан child
         * и сейчас находится в UTXO-set.
         */
        OutPoint createdByChild =
                outPoint(
                        'b',
                        0
                );

        StoredUtxo childUtxo =
                new StoredUtxo(
                        40_000L,
                        new byte[]{0x52},
                        101L,
                        false
                );

        UtxoChanges rollbackChanges =
                new UtxoChanges(
                        /*
                         * Удалить outputs child.
                         */
                        List.of(
                                createdByChild
                        ),

                        /*
                         * Восстановить outputs,
                         * которые child потратил.
                         */
                        List.of(
                                new CreatedUtxo(
                                        previouslySpent,
                                        restoredUtxo
                                )
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            /*
             * Имитируем состояние после подключения child.
             */
            blockIndexStore.save(parent);
            blockIndexStore.save(child);

            chainStateStore.saveActiveTipHash(
                    child.hash()
            );

            utxoStore.save(
                    createdByChild,
                    childUtxo
            );

            assertTrue(
                    utxoStore.find(previouslySpent)
                            .isEmpty()
            );

            assertEquals(
                    childUtxo,
                    utxoStore.find(createdByChild)
                            .orElseThrow()
            );

            BlockDisconnectionStorage storage =
                    new BlockDisconnectionStorage(
                            database,
                            utxoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            storage.commitDisconnectedBlock(
                    child,
                    rollbackChanges
            );

            /*
             * Старый потраченный UTXO восстановлен.
             */
            assertEquals(
                    restoredUtxo,
                    utxoStore.find(previouslySpent)
                            .orElseThrow()
            );

            /*
             * Output отключённого блока удалён.
             */
            assertTrue(
                    utxoStore.find(createdByChild)
                            .isEmpty()
            );

            /*
             * Active tip вернулся на parent.
             */
            assertEquals(
                    parent.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            /*
             * ВАЖНО:
             * индексы обоих блоков сохраняются.
             */
            assertEquals(
                    parent,
                    blockIndexStore.find(parent.hash())
                            .orElseThrow()
            );

            assertEquals(
                    child,
                    blockIndexStore.find(child.hash())
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRejectDisconnectingNonActiveBlock() {

        Path databasePath =
                tempDirectory.resolve("wrong-tip");

        StoredBlockIndex parent =
                parentBlockIndex();

        StoredBlockIndex child =
                childBlockIndex(parent);

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            blockIndexStore.save(parent);
            blockIndexStore.save(child);

            /*
             * Active tip уже parent,
             * поэтому child отключать нельзя.
             */
            chainStateStore.saveActiveTipHash(
                    parent.hash()
            );

            BlockDisconnectionStorage storage =
                    new BlockDisconnectionStorage(
                            database,
                            utxoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            storage.commitDisconnectedBlock(
                                    child,
                                    new UtxoChanges(
                                            List.of(),
                                            List.of()
                                    )
                            )
            );

            assertEquals(
                    parent.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRejectDisconnectingGenesis() {

        Path databasePath =
                tempDirectory.resolve("genesis");

        StoredBlockIndex genesis =
                parentBlockIndex();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            blockIndexStore.save(genesis);

            chainStateStore.saveActiveTipHash(
                    genesis.hash()
            );

            BlockDisconnectionStorage storage =
                    new BlockDisconnectionStorage(
                            database,
                            utxoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            storage.commitDisconnectedBlock(
                                    genesis,
                                    new UtxoChanges(
                                            List.of(),
                                            List.of()
                                    )
                            )
            );
        }
    }

    @Test
    void shouldHandleOutputCreatedAndSpentInsideSameBlock() {

        Path databasePath =
                tempDirectory.resolve(
                        "same-block-spend"
                );

        StoredBlockIndex parent =
                parentBlockIndex();

        StoredBlockIndex child =
                childBlockIndex(parent);

        OutPoint intermediate =
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

        /*
         * При disconnect:
         *
         * TX2 undo восстанавливает intermediate,
         * затем TX1 disconnect удаляет его.
         */
        UtxoChanges rollbackChanges =
                new UtxoChanges(
                        List.of(
                                intermediate
                        ),
                        List.of(
                                new CreatedUtxo(
                                        intermediate,
                                        intermediateUtxo
                                )
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            blockIndexStore.save(parent);
            blockIndexStore.save(child);

            chainStateStore.saveActiveTipHash(
                    child.hash()
            );

            BlockDisconnectionStorage storage =
                    new BlockDisconnectionStorage(
                            database,
                            utxoStore,
                            blockIndexStore,
                            chainStateStore
                    );

            storage.commitDisconnectedBlock(
                    child,
                    rollbackChanges
            );

            /*
             * PUT restore был раньше DELETE,
             * поэтому intermediate в итоговом
             * UTXO-set отсутствует.
             */
            assertTrue(
                    utxoStore.find(intermediate)
                            .isEmpty()
            );

            assertEquals(
                    parent.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    private static StoredBlockIndex parentBlockIndex() {

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00000000000000000000000000000000" +
                                        "00000000000000000000000000000000"
                        ),
                        Hash256.fromDisplayHex(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        ),
                        new UInt32(
                                1_700_000_000L
                        ),
                        new UInt32(
                                0x207FFFFFL
                        ),
                        new UInt32(
                                1
                        )
                );

        return new StoredBlockIndex(
                header.hash(),
                header,
                0L,
                header.previousBlockHash(),
                BigInteger.ONE
        );
    }

    private static StoredBlockIndex childBlockIndex(
            StoredBlockIndex parent
    ) {

        BlockHeader header =
                new BlockHeader(
                        1,
                        parent.hash(),
                        Hash256.fromDisplayHex(
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        ),
                        new UInt32(
                                1_700_000_001L
                        ),
                        new UInt32(
                                0x207FFFFFL
                        ),
                        new UInt32(
                                2
                        )
                );

        return new StoredBlockIndex(
                header.hash(),
                header,
                parent.height() + 1,
                parent.hash(),
                parent.chainWork()
                        .add(
                                BigInteger.ONE
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