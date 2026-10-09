package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommonAncestorFinderTest {

    @Test
    void shouldFindCommonAncestorOfForks() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        /*
         * fork:
         *
         *       b
         *      / \
         *     c   d
         *     |   |
         *     e   f
         */

        BlockIndex c =
                child(b, 3);

        BlockIndex e =
                child(c, 4);

        BlockIndex d =
                child(b, 5);

        BlockIndex f =
                child(d, 6);

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        for (BlockIndex index :
                new BlockIndex[]{
                        genesis,
                        a,
                        b,
                        c,
                        d,
                        e,
                        f
                }) {

            indexes.put(
                    index.hash(),
                    index
            );
        }

        BlockIndexLookup lookup =
                indexes::get;

        BlockIndex ancestor =
                CommonAncestorFinder.find(
                        e,
                        f,
                        lookup
                );

        assertEquals(
                b.hash(),
                ancestor.hash()
        );
    }

    @Test
    void shouldHandleDifferentHeights() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex a =
                child(genesis, 1);

        BlockIndex b =
                child(a, 2);

        BlockIndex c =
                child(b, 3);

        /*
         *        b
         *       / \
         *      c   d
         *      |
         *      e
         */

        BlockIndex d =
                child(b, 4);

        BlockIndex e =
                child(c, 5);

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        for (BlockIndex index :
                new BlockIndex[]{
                        genesis,
                        a,
                        b,
                        c,
                        d,
                        e
                }) {

            indexes.put(
                    index.hash(),
                    index
            );
        }

        BlockIndex ancestor =
                CommonAncestorFinder.find(
                        e,
                        d,
                        indexes::get
                );

        assertEquals(
                b.hash(),
                ancestor.hash()
        );
    }

    @Test
    void deepPersistentForkUsesBoundedReadsAndFindsExactForkHeight() {
        var main = new java.util.ArrayList<BlockIndex>();
        main.add(BlockIndexFactory.createGenesis(genesisHeader()));
        for (int height = 1; height <= 8192; height++) main.add(child(main.getLast(), height));
        var fork = new java.util.ArrayList<>(main.subList(0, 18));
        for (int height = 18; height <= 8193; height++) fork.add(child(fork.getLast(), height + 10000));
        try (var database = new ru.bitcoin.node.storage.rocksdb.RocksDbDatabase(directory)) {
            var store = new ru.bitcoin.node.storage.block.RocksDbBlockIndexStore(database);
            var all = new java.util.ArrayList<>(main);
            all.addAll(fork.subList(18, fork.size()));
            new ru.bitcoin.node.chain.storage.KnownHeaderStorage(database, store,
                    new ru.bitcoin.node.storage.chain.RocksDbChainStateStore(database))
                    .saveBatch(all, main.getLast());
            var lookup = new StoredBlockIndexLookup(store);
            long before = database.ioStats().gets();
            assertEquals(main.get(17).hash(), CommonAncestorFinder.find(main.getLast(), fork.getLast(), lookup).hash());
            long reads = database.ioStats().gets() - before;
            assertTrue(reads < 2000, "Deep fork must not read 16000 parents; actual reads=" + reads);
            assertEquals(main.get(4096).hash(), CommonAncestorFinder.find(main.getLast(), main.get(4096), lookup).hash());
            assertEquals(main.getLast().hash(), CommonAncestorFinder.find(main.getLast(), main.getLast(), lookup).hash());
        }
    }

    @Test
    void acceleratedSearchRejectsDifferentGenesis() {
        var first = BlockIndexFactory.createGenesis(genesisHeader());
        var second = BlockIndexFactory.createGenesis(child(first, 1).header());
        BlockIndexAncestorLookup lookup = new BlockIndexAncestorLookup() {
            public BlockIndex find(Hash256 hash) { throw new AssertionError("No parent reads expected"); }
            public BlockIndex ancestor(BlockIndex index, long height) { return index; }
        };
        assertThrows(IllegalStateException.class, () -> CommonAncestorFinder.find(first, second, lookup));
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    private static BlockIndex child(
            BlockIndex parent,
            long nonce
    ) {

        BlockHeader header =
                new BlockHeader(
                        1,
                        parent.hash(),
                        Hash256.fromDisplayHex(
                                String.format(
                                        "%064x",
                                        nonce
                                )
                        ),
                        new UInt32(
                                1231006505L
                                        + parent.height() * 600
                                        + 600
                        ),
                        new UInt32(0x1D00FFFFL),
                        new UInt32(nonce)
                );

        return BlockIndexFactory.createChild(
                parent,
                header
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
}