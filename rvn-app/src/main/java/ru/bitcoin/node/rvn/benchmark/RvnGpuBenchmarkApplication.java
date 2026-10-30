package ru.bitcoin.node.rvn.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.bitcoin.node.rvn.app.mining.RvnRealTargetGpuBenchmark;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Separate Spring entry point: does not scan the node or open its chainstate. */
@SpringBootApplication
@EnableConfigurationProperties(RvnGpuBenchmarkApplication.Settings.class)
public class RvnGpuBenchmarkApplication {
    private static final Logger LOG = LoggerFactory.getLogger(RvnGpuBenchmarkApplication.class);

    public static void main(String[] args) {
        try (var context = application().run(args)) {
            // ApplicationRunner executes the benchmark before run() returns.
        }
    }

    static SpringApplication application() {
        var app = new SpringApplication(RvnGpuBenchmarkApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("spring.config.name", "application-rvn-benchmark"));
        return app;
    }

    @ConfigurationProperties("ravencoin.benchmark")
    public record Settings(Path snapshot, Path worker, Path outputDirectory, String python, long seconds, boolean dryRun) {
        public Settings {
            if (snapshot == null || worker == null || outputDirectory == null || python == null || python.isBlank() || seconds < 0)
                throw new IllegalArgumentException("Configure benchmark snapshot, worker, output-directory, python and nonnegative seconds");
        }
    }

    @Bean
    ApplicationRunner benchmark(Settings settings) {
        return args -> {
            if (!Files.isRegularFile(settings.snapshot())) throw new IllegalArgumentException(
                    "Benchmark snapshot not found: " + settings.snapshot().toAbsolutePath()
                            + "; set ravencoin.benchmark.snapshot to a captured real block");
            if (!Files.isRegularFile(settings.worker())) throw new IllegalArgumentException("CUDA worker not found: " + settings.worker());
            LOG.info("GPU target benchmark: snapshot={}, seconds={}, dryRun={}",
                    settings.snapshot().toAbsolutePath(), settings.seconds(), settings.dryRun());
            if (settings.dryRun()) {
                LOG.info("Benchmark configuration OK; dry run complete");
                return;
            }
            Files.createDirectories(settings.outputDirectory());
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS"));
            Path run = Files.createTempDirectory(settings.outputDirectory(), "run-" + stamp + "-");
            LOG.info("Benchmark artifacts={}; create stop-requested here for graceful cancellation", run.toAbsolutePath());
            RvnRealTargetGpuBenchmark.main(new String[]{"run", settings.snapshot().toString(), settings.worker().toString(),
                    run.resolve("cuda").toString(), settings.python(), Long.toString(settings.seconds())});
        };
    }
}
