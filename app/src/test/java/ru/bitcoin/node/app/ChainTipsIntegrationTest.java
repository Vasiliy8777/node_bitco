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

class ChainTipsIntegrationTest {
    @TempDir
    Path directory;

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
    void reportsActiveValidForkAndInvalidTipsAcrossRestart() throws Exception {
        Block original102 = coreSpend(102);
        Block alternative102 = reorg(102);
        Block alternative103 = reorg(103);
        try (var db = new RocksDbDatabase(directory)) {
            var validation = service(db);
            for (int height = 1; height <= 102; height++)
                assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(coreSpend(height)));
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, validation.processBlock(alternative102));
            assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(alternative103));

            var tips = validation.chainTips();
            assertEquals(2, tips.size());
            assertEquals("active", tips.stream().filter(t -> t.hash().equals(alternative103.hash())).findFirst().orElseThrow().status());
            var old = tips.stream().filter(t -> t.hash().equals(original102.hash())).findFirst().orElseThrow();
            assertEquals("valid-fork", old.status());
            assertEquals(1, old.branchLength());

            validation.invalidateBlock(alternative102.hash());
            tips = validation.chainTips();
            assertEquals("active", tips.stream().filter(t -> t.hash().equals(original102.hash())).findFirst().orElseThrow().status());
            var invalid = tips.stream().filter(t -> t.hash().equals(alternative103.hash())).findFirst().orElseThrow();
            assertEquals("invalid", invalid.status());
            assertEquals(2, invalid.branchLength());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var tips = service(db).chainTips();
            assertEquals("invalid", tips.stream().filter(t -> t.hash().equals(alternative103.hash())).findFirst().orElseThrow().status());
        }
    }
}
