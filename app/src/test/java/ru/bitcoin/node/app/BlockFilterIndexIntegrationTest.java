package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BlockFilterIndexIntegrationTest {
    @TempDir Path directory;

    @Test
    void persistsBasicFiltersAndResumesAfterRestart() {
        var parameters = NetworkParametersRegistry.regtest();
        ru.bitcoin.node.protocol.block.Block mined;
        byte[] firstFilter;
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, false, false, true);
            assertTrue(service.blockFilterIndexEnabled());
            assertTrue(service.blockFilter(parameters.genesisBlockHash()).isPresent());
            try (var in = java.util.Objects.requireNonNull(getClass().getResourceAsStream("/core-spend/1.bin"))) {
                mined = ru.bitcoin.node.protocol.serialization.BlockParser.parse(in.readAllBytes());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            assertEquals(ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED, service.processBlock(mined));
            var record = service.blockFilter(mined.hash()).orElseThrow();
            firstFilter = record.filter();
            assertTrue(firstFilter.length > 0);
            assertNotNull(record.header());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var restarted = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool(),
                    0L, parameters.defaultAssumeValid(), false, false, false, true);
            assertArrayEquals(firstFilter, restarted.blockFilter(mined.hash()).orElseThrow().filter());
        }
    }

    @Test
    void disabledIndexDoesNotExposeFilters() {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            assertFalse(service.blockFilterIndexEnabled());
            assertTrue(service.blockFilter(parameters.genesisBlockHash()).isEmpty());
        }
    }
}
