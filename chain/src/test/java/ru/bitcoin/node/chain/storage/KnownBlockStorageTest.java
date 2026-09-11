package ru.bitcoin.node.chain.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnownBlockStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPersistKnownBlockWithoutActivatingIt() {

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve("db")
                     )) {

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(database);

            RocksDbUtxoStore utxoStore =
                    new RocksDbUtxoStore(database);

            RocksDbUndoStore undoStore =
                    new RocksDbUndoStore(database);

            KnownBlockStorage storage =
                    new KnownBlockStorage(
                            database,
                            blockStore,
                            blockIndexStore
                    );

            Hash256 parentHash =
                    Hash256.fromDisplayHex(
                            "11".repeat(32)
                    );

            Transaction coinbase =
                    new Transaction(
                            1,
                            List.of(
                                    new TxIn(
                                            OutPoint.coinbase(),
                                            new byte[]{0x01},
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

            BlockHeader header =
                    new BlockHeader(
                            1,
                            parentHash,
                            Hash256.fromDisplayHex(
                                    "22".repeat(32)
                            ),
                            new UInt32(
                                    1_700_000_000L
                            ),
                            new UInt32(
                                    0x207FFFFFL
                            ),
                            new UInt32(1)
                    );

            Block block =
                    new Block(
                            header,
                            List.of(coinbase)
                    );

            BlockIndex blockIndex =
                    new BlockIndex(
                            block.hash(),
                            header,
                            101L,
                            parentHash,
                            BigInteger.valueOf(2000)
                    );

            storage.save(
                    block,
                    blockIndex
            );

            /*
             * Block body сохранён.
             */
            assertEquals(
                    block,
                    blockStore
                            .find(block.hash())
                            .orElseThrow()
            );

            /*
             * BlockIndex сохранён.
             */
            assertTrue(
                    blockIndexStore
                            .find(block.hash())
                            .isPresent()
            );

            /*
             * Но блок НЕ стал active tip.
             */
            assertTrue(
                    chainStateStore
                            .loadActiveTipHash()
                            .isEmpty()
            );

            /*
             * Его coinbase НЕ попала в UTXO.
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
             * Undo тоже не создаётся просто
             * от факта знания блока.
             */
            assertTrue(
                    undoStore
                            .find(block.hash())
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldRejectMismatchedBlockAndIndex() {

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "mismatch-db"
                             )
                     )) {

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(database);

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(database);

            KnownBlockStorage storage =
                    new KnownBlockStorage(
                            database,
                            blockStore,
                            blockIndexStore
                    );

            Hash256 parentHash =
                    Hash256.fromDisplayHex(
                            "33".repeat(32)
                    );

            BlockHeader header =
                    new BlockHeader(
                            1,
                            parentHash,
                            Hash256.fromDisplayHex(
                                    "44".repeat(32)
                            ),
                            new UInt32(
                                    1_700_000_100L
                            ),
                            new UInt32(
                                    0x207FFFFFL
                            ),
                            new UInt32(2)
                    );

            Block block =
                    new Block(
                            header,
                            List.of()
                    );

            BlockHeader differentHeader =
                    new BlockHeader(
                            1,
                            parentHash,
                            Hash256.fromDisplayHex(
                                    "55".repeat(32)
                            ),
                            new UInt32(
                                    1_700_000_100L
                            ),
                            new UInt32(
                                    0x207FFFFFL
                            ),
                            new UInt32(2)
                    );

            BlockIndex wrongIndex =
                    new BlockIndex(
                            differentHeader.hash(),
                            differentHeader,
                            101L,
                            parentHash,
                            BigInteger.valueOf(2000)
                    );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> storage.save(
                            block,
                            wrongIndex
                    )
            );

            /*
             * Проверяем, что до batch write
             * ничего не было записано.
             */
            assertTrue(
                    blockStore
                            .find(block.hash())
                            .isEmpty()
            );

            assertTrue(
                    blockIndexStore
                            .find(
                                    wrongIndex.hash()
                            )
                            .isEmpty()
            );
        }
    }
}