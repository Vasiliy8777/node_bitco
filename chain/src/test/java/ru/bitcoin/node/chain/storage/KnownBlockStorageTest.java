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
    void shouldKeepCommittedHeaderIndexWithoutReadingOrRewritingIt() {
        Path path = tempDirectory.resolve("known-header");
        Block block = testBlock();
        BlockIndex index = testIndex(block, 101);
        try (RocksDbDatabase database = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(database);
            indexes.save(ru.bitcoin.node.chain.BlockIndexStorageMapper.toStored(index));
            var storage = new KnownBlockStorage(database, new RocksDbBlockStore(database), indexes);
            byte prefix = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_INDEX;
            long version = database.namespaceVersion(prefix);
            var before = database.namespaceIoStats();
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                storage.save(batch, block, index, index);
                database.write(batch);
            }
            assertEquals(0, database.namespaceIoStats().minus(before).gets(prefix));
            assertEquals(version, database.namespaceVersion(prefix));
            assertTrue(storage.hasBody(block.hash()));
            assertTrue(new RocksDbChainStateStore(database).loadActiveTipHash().isEmpty());
        }
        try (RocksDbDatabase reopened = new RocksDbDatabase(path)) {
            assertEquals(block, new RocksDbBlockStore(reopened).find(block.hash()).orElseThrow());
            assertEquals(ru.bitcoin.node.chain.BlockIndexStorageMapper.toStored(index),
                    new RocksDbBlockIndexStore(reopened).find(index.hash()).orElseThrow());
            assertTrue(new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(reopened)
                    .hasData(block.hash()));
        }
    }

    @Test
    void shouldPersistNewOrChangedIndexThroughRegularPath() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("replacement"))) {
            var indexes = new RocksDbBlockIndexStore(database);
            var storage = new KnownBlockStorage(database, new RocksDbBlockStore(database), indexes);
            Block block = testBlock();
            BlockIndex old = testIndex(block, 101);
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                storage.save(batch, block, old, null);
                database.write(batch);
            }
            BlockIndex replacement = testIndex(block, 102);
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                storage.save(batch, block, replacement, old);
                database.write(batch);
            }
            assertEquals(ru.bitcoin.node.chain.BlockIndexStorageMapper.toStored(replacement),
                    indexes.find(block.hash()).orElseThrow());
            assertEquals(1, database.countPrefix(
                    ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_HEIGHT_INDEX));
            assertEquals(1, database.countPrefix(
                    ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_WORK_INDEX));
        }
    }

    private static Block testBlock() {
        return new Block(new BlockHeader(1, Hash256.fromDisplayHex("33".repeat(32)),
                Hash256.fromDisplayHex("44".repeat(32)), new UInt32(1_700_000_100L),
                new UInt32(0x207FFFFFL), new UInt32(2)), List.of());
    }

    private static BlockIndex testIndex(Block block, long height) {
        return new BlockIndex(block.hash(), block.header(), height,
                block.header().previousBlockHash(), BigInteger.valueOf(height * 20));
    }

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
