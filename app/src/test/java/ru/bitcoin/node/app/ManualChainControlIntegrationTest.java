package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ManualChainControlIntegrationTest {
    @TempDir Path directory;

    private byte[] resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(path))) {
            return in.readAllBytes();
        }
    }

    private Block coreSpend(int height) throws Exception {
        return BlockParser.parse(resource("/core-spend/" + height + ".bin"));
    }

    private Block reorg(int height) throws Exception {
        return BlockParser.parse(resource("/core-reorg/" + height + ".bin"));
    }

    private NodeValidationService service(RocksDbDatabase db) {
        return new NodeValidationService(db, NetworkParametersRegistry.regtest(), () -> 1_800_000_000L, new Mempool());
    }

    @Test
    void invalidateAndReconsiderPersistAcrossRestartAndReactivateBestEligibleBranch() throws Exception {
        Block original102 = coreSpend(102);
        Block alternative102 = reorg(102);
        Block alternative103 = reorg(103);

        try (var db = new RocksDbDatabase(directory)) {
            var validation = service(db);
            for (int height = 1; height <= 102; height++) {
                assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(coreSpend(height)));
            }
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, validation.processBlock(alternative102));
            assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(alternative103));
            assertEquals(alternative103.hash(), validation.activeTip().hash());

            validation.invalidateBlock(alternative102.hash());
            assertTrue(validation.isBlockFailed(alternative102.hash()));
            assertTrue(validation.isBlockFailed(alternative103.hash()));
            assertEquals(original102.hash(), validation.activeTip().hash());
        }

        try (var db = new RocksDbDatabase(directory)) {
            var validation = service(db);
            assertEquals(original102.hash(), validation.activeTip().hash());
            assertTrue(validation.isBlockFailed(alternative103.hash()));

            validation.reconsiderBlock(alternative102.hash());
            assertFalse(validation.isBlockFailed(alternative102.hash()));
            assertFalse(validation.isBlockFailed(alternative103.hash()));
            assertEquals(alternative103.hash(), validation.activeTip().hash());
        }

        try (var db = new RocksDbDatabase(directory)) {
            var validation = service(db);
            assertEquals(alternative103.hash(), validation.activeTip().hash());
            assertFalse(validation.isBlockFailed(alternative102.hash()));
        }
    }

    @Test
    void genesisCannotBeInvalidated() throws Exception {
        try (var db = new RocksDbDatabase(directory)) {
            var validation = service(db);
            var genesis = validation.activeTip();
            var error = assertThrows(IllegalArgumentException.class,
                    () -> validation.invalidateBlock(genesis.hash()));
            assertTrue(error.getMessage().contains("Genesis"));
        }
    }
}
