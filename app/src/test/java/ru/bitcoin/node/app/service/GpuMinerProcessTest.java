package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "bitcoin.gpu.test", matches = "true")
class GpuMinerProcessTest {
    @TempDir Path directory;

    @Test void passesCredentialsAndArgumentsAndStopsOwnedProcess() throws Exception {
        Path script = directory.resolve("worker.py");
        Path marker = directory.resolve("pid.txt");
        Files.writeString(script, """
                import os, sys, time
                from pathlib import Path
                assert os.environ['BITCOIN_STRATUM_PASSWORD'] == 'test-only-password'
                assert sys.argv[1:] == ['--host', '127.0.0.1', '--port', '3333', '--worker', 'miner.test', '--device', '0']
                Path(__file__).with_name('pid.txt').write_text(str(os.getpid()))
                time.sleep(60)
                """);
        long pid;
        try (var worker = new GpuMinerProcess(System.getProperty("bitcoin.gpu.python", "python"), script,
                0, 3333, "miner.test", "test-only-password")) {
            worker.start();
            pid = Long.parseLong(Files.readString(marker));
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
        }
        assertTrue(ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true));
    }

    @Test void reportsWorkerStartupFailure() throws Exception {
        Path script = directory.resolve("failed.py");
        Files.writeString(script, "raise SystemExit(7)\n");
        try (var worker = new GpuMinerProcess(System.getProperty("bitcoin.gpu.python", "python"), script,
                0, 3333, "miner.test", "test-only-password")) {
            var error = assertThrows(java.io.IOException.class, worker::start);
            assertTrue(error.getMessage().contains("code 7"));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void springBootStartsAndStopsActualCudaWorkerWithGeneratedPassword(CapturedOutput output) throws Exception {
        Path script = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .resolve("tools/gpu-miner/miner.py").toAbsolutePath();
        Process child;
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(
                ru.bitcoin.node.app.BitcoinNodeApplication.class).run(
                "--spring.profiles.active=regtest,gpu", "--bitcoin.node.auto-start=false",
                "--bitcoin.p2p.listen=false", "--bitcoin.rpc.enabled=false", "--bitcoin.stratum.port=0",
                "--bitcoin.data-directory=" + directory.resolve("node"), "--bitcoin.mining.payout-address=",
                "--bitcoin.gpu-miner.script=" + script, "--bitcoin.stratum.password=${random.uuid}")) {
            var worker = context.getBean(GpuMinerProcess.class);
            child = (Process)org.springframework.test.util.ReflectionTestUtils.getField(worker, "process");
            assertNotNull(child);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (!output.getOut().contains("Stratum authorized") && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(output.getOut().contains("Stratum authorized"), output.getOut());
            assertTrue(child.isAlive());
            assertFalse(context.getBean(NodeLifecycleService.class).isMiningReady());
        }
        assertFalse(child.isAlive(), "Spring context shutdown must stop the CUDA child");
    }
}
