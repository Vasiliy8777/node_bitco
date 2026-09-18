package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderBatchProcessorTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;

    @TempDir
    Path tempDirectory;

    @Test
    void shouldProcessAndPersistSequentialHeaders() {

        Path databasePath =
                tempDirectory.resolve(
                        "headers"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(
                            database
                    );

            BlockIndex genesis =
                    createGenesisIndex();

            store.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            store
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            store
                    );

            BlockHeader header1 =
                    findValidHeader(
                            genesis.hash(),
                            1_700_000_001L
                    );

            BlockHeader header2 =
                    findValidHeader(
                            header1.hash(),
                            1_700_000_002L
                    );

            BlockHeader header3 =
                    findValidHeader(
                            header2.hash(),
                            1_700_000_003L
                    );

            List<BlockIndex> result =
                    batchProcessor.process(
                            List.of(
                                    header1,
                                    header2,
                                    header3
                            )
                    );

            assertEquals(
                    3,
                    result.size()
            );

            assertEquals(
                    1L,
                    result.get(0).height()
            );

            assertEquals(
                    2L,
                    result.get(1).height()
            );

            assertEquals(
                    3L,
                    result.get(2).height()
            );

            assertEquals(
                    genesis.hash(),
                    result.get(0)
                            .previousBlockHash()
            );

            assertEquals(
                    result.get(0).hash(),
                    result.get(1)
                            .previousBlockHash()
            );

            assertEquals(
                    result.get(1).hash(),
                    result.get(2)
                            .previousBlockHash()
            );

            assertTrue(
                    store.find(
                            header1.hash()
                    ).isPresent()
            );

            assertTrue(
                    store.find(
                            header2.hash()
                    ).isPresent()
            );

            assertTrue(
                    store.find(
                            header3.hash()
                    ).isPresent()
            );

            assertEquals(
                    3L,
                    lookup.find(
                            header3.hash()
                    ).height()
            );
        }
    }

    private static BlockIndex createGenesisIndex() {

        Hash256 previous =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        BlockHeader header =
                findValidHeader(
                        previous,
                        1_700_000_000L
                );

        return new BlockIndex(
                header.hash(),
                header,
                0L,
                previous,
                BigInteger.ONE
        );
    }

    private static BlockHeader findValidHeader(
            Hash256 previousBlockHash,
            long timestamp
    ) {

        for (long nonce = 0;
             nonce <= UInt32.MAX_VALUE;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            previousBlockHash,
                            Hash256.fromDisplayHex(
                                    "11".repeat(32)
                            ),
                            new UInt32(
                                    timestamp
                            ),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    nonce
                            )
                    );

            if (ProofOfWork.isValid(
                    header,
                    REGTEST
            )) {
                return header;
            }
        }

        throw new IllegalStateException(
                "Could not find valid regtest nonce"
        );
    }
}