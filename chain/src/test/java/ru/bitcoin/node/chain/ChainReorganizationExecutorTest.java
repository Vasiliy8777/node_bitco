package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainReorganizationExecutorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldNotChangeStateWhenConnectBlockBodyIsMissing() {

        Path databasePath =
                tempDirectory.resolve("missing-block");

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

        ChainUpdate update =
                new ChainUpdate(
                        oldTip,
                        newTip,
                        new ReorganizationPlan(
                                oldTip,
                                List.of(),
                                List.of(newTip)
                        )
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
             * BlockIndex известен,
             * но Block body намеренно НЕ сохраняем.
             */
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

            ChainTransitionManager transitionManager =
                    new ChainTransitionManager(
                            chainState,
                            transitionStorage
                    );

            StoredBlockIndexLookup blockIndexLookup =
                    new StoredBlockIndexLookup(
                            blockIndexStore
                    );

            RocksDbBlockFailureStore failureStore =
                    new RocksDbBlockFailureStore(
                            database
                    );

            BlockFailureResolver failureResolver =
                    new BlockFailureResolver(
                            blockIndexLookup,
                            failureStore
                    );

            BlockFailureManager failureManager =
                    new BlockFailureManager(
                            database,
                            failureStore,
                            blockIndexStore,
                            chainStateStore,
                            failureResolver
                    );

            ChainReorganizationExecutor executor =
                    new ChainReorganizationExecutor(
                            blockStore,
                            undoStore,
                            utxoStore,
                            transitionManager,
                            NetworkParametersRegistry.regtest(),
                            blockIndexLookup,
                            failureManager::markFailed
                    );


            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> executor.execute(update)
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    "Block body not found for connect"
                            )
            );

            /*
             * RAM остался на oldTip.
             */
            assertEquals(
                    oldTip,
                    chainState.activeTip()
            );

            /*
             * Disk тоже остался на oldTip.
             */
            assertEquals(
                    oldTip.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            /*
             * Отсутствующий block body — infrastructure/storage
             * failure, а НЕ доказательство consensus-invalid.
             *
             * InvalidBlockObserver не должен вызываться,
             * поэтому permanent FAILED marker появиться не может.
             */
            assertFalse(
                    failureStore.isFailed(
                            newTip.hash()
                    ),
                    "Missing block body must not permanently mark the block as consensus-invalid"
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