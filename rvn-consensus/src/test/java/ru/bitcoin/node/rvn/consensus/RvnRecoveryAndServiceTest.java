package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnRecoveryAndServiceTest {
    @TempDir
    Path dir;

    @Test
    void saveAndReverifyOnRecovery() throws Exception {
        var original = index(10);
        original.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)),
                TEST_ONLY, new RvnHeaderFormatPolicy(0));
        Path path = dir.resolve("rvn-candidates.dat");
        RvnHeaderRecovery.save(path, original);
        assertThrows(SecurityException.class, () -> RvnHeaderRecovery.recover(
                path, () -> index(10), new RvnHeaderFormatPolicy(0), RvnHeaderProofGate.unavailable()));
        var recovered = RvnHeaderRecovery.recover(
                path, () -> index(10), new RvnHeaderFormatPolicy(0), TEST_ONLY);
        assertEquals(2, recovered.restored());
        assertEquals(id(2), recovered.index().bestId());
        assertEquals(2, recovered.index().size());
    }

    @Test
    void admissionMetricsAndAtomicFailure() {
        var index = index(10);
        var metrics = new RvnHeaderAdmissionMetrics();
        var service = new RvnHeaderAdmissionService(index, new RvnHeaderFormatPolicy(0), TEST_ONLY, metrics);
        assertEquals(1, service.accept(List.of(item(1, id(90), 1))).size());
        assertThrows(SecurityException.class, () -> service.accept(
                List.of(item(2, id(1), 2), item(3, id(2), 5))));
        assertEquals(1, index.size());
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.acceptedHeaders());
        assertEquals(1, snapshot.rejectedBatches());
        assertEquals(2, snapshot.attemptedBatches());
    }
}
