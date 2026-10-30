package ru.bitcoin.node.rvn.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService;
import ru.bitcoin.node.rvn.pow.RvnCudaKawpowWorker;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnGpuBenchmarkApplicationTest {
    @TempDir Path directory;

    @Test void playEntryPointBindsSettingsWithoutStartingNodeOrGpuInDryRun() throws Exception {
        Path snapshot = Files.write(directory.resolve("snapshot.bin"), new byte[0]);
        Path worker = Files.write(directory.resolve("worker.py"), new byte[0]);
        try (var context = RvnGpuBenchmarkApplication.application().run(
                "--ravencoin.benchmark.dry-run=true",
                "--ravencoin.benchmark.snapshot=" + snapshot,
                "--ravencoin.benchmark.worker=" + worker,
                "--logging.file.name=target/rvn-benchmark-spring-test.log",
                "--ravencoin.benchmark.output-directory=" + directory.resolve("output"),
                "--ravencoin.benchmark.seconds=17")) {
            var settings = context.getBean(RvnGpuBenchmarkApplication.Settings.class);
            assertEquals(17, settings.seconds());
            assertEquals(snapshot, settings.snapshot());
            assertTrue(settings.dryRun());
            assertEquals(0, context.getBeanNamesForType(RvnHeaderSyncService.class).length);
            assertEquals(0, context.getBeanNamesForType(RvnCudaKawpowWorker.class).length);
            assertTrue(Files.isRegularFile(Path.of("target/rvn-benchmark-spring-test.log")));
        }
    }
}
