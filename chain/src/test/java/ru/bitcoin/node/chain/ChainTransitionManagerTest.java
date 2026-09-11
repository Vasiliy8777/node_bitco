package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChainTransitionManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldCommitDiskBeforeUpdatingRam() {

        Path databasePath =
                tempDirectory.resolve("transition-manager");

        BlockIndex oldTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L,
                        BigInteger.valueOf(1000)
                );

        BlockIndex newTip =
                blockIndex(
                        'b',
                        oldTip.hash(),
                        101L,
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

        BlockReorganizationChanges changes =
                new BlockReorganizationChanges(
                        new UtxoChanges(
                                List.of(),
                                List.of()
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

            blockIndexStore.save(
                    toStored(oldTip)
            );

            blockIndexStore.save(
                    toStored(newTip)
            );

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

            ChainTransitionManager manager =
                    new ChainTransitionManager(
                            chainState,
                            transitionStorage
                    );

            manager.commit(
                    update,
                    changes
            );

            assertEquals(
                    newTip,
                    chainState.activeTip()
            );

            assertEquals(
                    newTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldRejectStaleRamUpdateBeforeTouchingDisk() {

        Path databasePath =
                tempDirectory.resolve("stale-ram");

        BlockIndex actualTip =
                blockIndex(
                        'a',
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        100L,
                        BigInteger.valueOf(1000)
                );

        BlockIndex staleOldTip =
                blockIndex(
                        'b',
                        Hash256.fromDisplayHex(
                                "11".repeat(32)
                        ),
                        100L,
                        BigInteger.valueOf(900)
                );

        BlockIndex newTip =
                blockIndex(
                        'c',
                        staleOldTip.hash(),
                        101L,
                        BigInteger.valueOf(2000)
                );

        ChainState chainState =
                new ChainState(actualTip);

        ChainUpdate update =
                new ChainUpdate(
                        staleOldTip,
                        newTip,
                        new ReorganizationPlan(
                                staleOldTip,
                                List.of(),
                                List.of(newTip)
                        )
                );

        BlockReorganizationChanges changes =
                new BlockReorganizationChanges(
                        new UtxoChanges(
                                List.of(),
                                List.of()
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

            blockIndexStore.save(
                    toStored(actualTip)
            );

            blockIndexStore.save(
                    toStored(newTip)
            );

            chainStateStore.saveActiveTipHash(
                    actualTip.hash()
            );

            ChainTransitionManager manager =
                    new ChainTransitionManager(
                            chainState,
                            new RocksDbChainTransitionStorage(
                                    database,
                                    utxoStore,
                                    undoStore,
                                    blockIndexStore,
                                    chainStateStore
                            )
                    );

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            manager.commit(
                                    update,
                                    changes
                            )
            );

            assertEquals(
                    actualTip,
                    chainState.activeTip()
            );

            assertEquals(
                    actualTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
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
            BlockIndex blockIndex
    ) {

        return new StoredBlockIndex(
                blockIndex.hash(),
                blockIndex.header(),
                blockIndex.height(),
                blockIndex.previousBlockHash(),
                blockIndex.chainWork()
        );
    }
}