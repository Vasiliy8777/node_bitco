package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundValidationReportTest {
    @TempDir Path directory;

    @Test void reportsCommittedBackgroundWorkAndOmitsCompletedValidation() {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            assertTrue(validation.backgroundValidationReport().isEmpty());
            var genesis = validation.activeTip();
            var target = parameters.assumeUtxoData().getFirst();
            // Seed committed snapshot metadata/progress; activation validation is
            // covered separately by the snapshot verifier and restart fixtures.
            new RocksDbSnapshotChainStateStore(db).activate(genesis.hash(), 0, target.blockHash(), target.height());
            var progress = new RocksDbAssumeUtxoBackgroundStore(db);
            progress.initialize(genesis.hash(), 0);
            var report = validation.backgroundValidationReport().orElseThrow();
            assertEquals(target.height(), report.snapshotHeight());
            assertEquals(0, report.blocks());
            assertEquals(genesis.hash(), report.bestBlockHash());
            assertEquals(genesis.chainWork(), report.chainWork());
            assertEquals(genesis.header().timestamp().value(), report.medianTime());
            assertEquals(1.0 / target.chainTxCount(), report.verificationProgress());
            assertEquals(target.blockHash(), validation.chainStates().getLast().snapshotBlockHash());
            progress.mark(RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED, genesis.hash(), 0);
            assertTrue(validation.backgroundValidationReport().isEmpty());
        }
    }
}
