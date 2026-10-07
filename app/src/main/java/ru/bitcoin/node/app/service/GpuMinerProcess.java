package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owns the CUDA worker so Spring Boot Run/Stop controls the complete mining session. */
public final class GpuMinerProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GpuMinerProcess.class);
    private final String python;
    private final Path script;
    private final int device;
    private final int port;
    private final String worker;
    private final String password;
    private Process process;
    private volatile boolean closed;

    public GpuMinerProcess(String python, Path script, int device, int port, String worker, String password) {
        this.python = python;
        this.script = script.toAbsolutePath().normalize();
        this.device = device;
        this.port = port;
        this.worker = worker;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() throws IOException, InterruptedException {
        if (closed || process != null) throw new IllegalStateException("GPU worker already started or closed");
        if (!Files.isRegularFile(script)) throw new IOException("GPU miner script not found: " + script + "; set working directory to the project root");
        var builder = new ProcessBuilder(List.of(python, "-u", script.toString(), "--host", "127.0.0.1",
                "--port", Integer.toString(port), "--worker", worker, "--device", Integer.toString(device)))
                .redirectErrorStream(true);
        builder.environment().put("BITCOIN_STRATUM_PASSWORD", password);
        process = builder.start();
        process.getOutputStream().close();
        var output = process.getInputStream();
        Thread.ofVirtual().name("cuda-worker-console").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) LOG.info("CUDA: {}", line);
            } catch (IOException exception) {
                if (!closed) LOG.warn("Cannot read CUDA worker output", exception);
            }
        });
        if (process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            throw new IOException("CUDA worker exited with code " + process.exitValue() + "; inspect the preceding console output");
        }
        LOG.info("CUDA worker started, PID {}; waits for synchronized Stratum work", process.pid());
        process.onExit().thenAccept(exited -> {
            if (!closed) LOG.error("CUDA worker exited with code {}; restart the Spring Boot application to resume mining", exited.exitValue());
        });
    }

    @Override public synchronized void close() throws InterruptedException {
        closed = true;
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) LOG.warn("CUDA worker PID {} has not exited", process.pid());
            }
            LOG.info("CUDA worker stopped");
        }
    }
}
