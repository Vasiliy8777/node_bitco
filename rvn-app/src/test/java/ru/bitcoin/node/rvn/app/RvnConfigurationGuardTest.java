package ru.bitcoin.node.rvn.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RvnConfigurationGuardTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private SpringApplication application(Map<String, Object> properties) {
        var app = new SpringApplication(RavencoinNodeApplication.class);
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("rvn-test", properties));
        app.setEnvironment(environment);
        return app;
    }

    private static String[] arguments() {
        return new String[]{"--spring.main.banner-mode=off", "--logging.file.name=", "--ravencoin.network=mainnet",
                "--ravencoin.sync.enabled=false", "--ravencoin.mining.address=",
                "--ravencoin.pow.self-test-enabled=false", "--ravencoin.p2p.probe-enabled=false",
                "--ravencoin.p2p.session-enabled=false", "--logging.level.root=OFF"};
    }

    @Test
    void bodyDownloadCannotSilentlyStartWithoutSync() {
        var app = application(Map.of("RVN_SYNC_BLOCKS", "true", "RVN_MINING_ENABLED", "false"));
        var failure = assertThrows(IllegalArgumentException.class, () -> app.run(arguments()));
        assertTrue(failure.getMessage().contains("body download requires"));
    }

    @Test
    void environmentMiningSwitchStillRefusesUnimplementedLifecycle() {
        var app = application(Map.of("RVN_MINING_ENABLED", "true", "RVN_SYNC_BLOCKS", "false"));
        var failure = assertThrows(IllegalStateException.class, () -> app.run(arguments()));
        assertTrue(failure.getMessage().contains("mining NOT implemented"));
    }

    @Test
    void environmentDirectoryBindsInOfflineMode() {
        var app = application(Map.of("RVN_DATA_DIRECTORY", "./target/test-rvn-data", "RVN_HOT_DIRECTORY", "./target/test-rvn-hot",
                "RVN_MINING_ENABLED", "false", "RVN_SYNC_BLOCKS", "false"));
        try (var context = app.run(arguments())) {
            assertEquals("./target/test-rvn-data", context.getEnvironment().getProperty("ravencoin.data-directory"));
            var directories = context.getBean(ru.bitcoin.node.rvn.app.config.RvnDataDirectories.class);
            var root = java.nio.file.Path.of("./target/test-rvn-data").toAbsolutePath().normalize();
            var hot = java.nio.file.Path.of("./target/test-rvn-hot").toAbsolutePath().normalize();
            assertEquals(root, directories.root());
            assertEquals(hot.resolve("headers"), directories.headers());
            assertEquals(hot.resolve("coins"), directories.coins());
            assertEquals(root.resolve("blocks"), directories.blocks());
            assertEquals(root.resolve("undo"), directories.undo());
            assertEquals(root.resolve("archives"), directories.archives());
            assertEquals(root.resolve("dag"), directories.dag());
            assertEquals(root.resolve("logs"), directories.logs());
            assertEquals(hot.resolve("tmp"), directories.temporary());
            assertFalse(context.containsBean("rvnHeaderStore"));
        }
    }

    @Test
    void nativeReplayRequiresSyncAndBodiesBeforeOpeningAnyDatabase() {
        var directory = temporary.resolve("invalid-replay");
        var app = application(Map.of("RVN_NATIVE_REPLAY", "true", "RVN_MINING_ENABLED", "false",
                "RVN_SYNC_BLOCKS", "false", "RVN_DATA_DIRECTORY", directory.toString()));
        var failure = assertThrows(RuntimeException.class, () -> app.run(arguments()));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("native replay requires header sync and block download"));
        assertFalse(java.nio.file.Files.exists(directory));
    }

    @Test
    void changingToHotRootCannotSilentlyIgnoreAnExistingColdDatabase() throws Exception {
        var cold = temporary.resolve("legacy"); var hot = temporary.resolve("new-hot");
        java.nio.file.Files.createDirectories(cold.resolve("headers"));
        java.nio.file.Files.writeString(cold.resolve("headers/CURRENT"), "untouched legacy metadata");
        var app = application(Map.of("RVN_DATA_DIRECTORY", cold.toString(), "RVN_HOT_DIRECTORY", hot.toString(),
                "RVN_NATIVE_REPLAY", "false", "RVN_MINING_ENABLED", "false", "RVN_SYNC_BLOCKS", "false"));
        var args = java.util.Arrays.stream(arguments()).map(arg -> arg.equals("--ravencoin.sync.enabled=false")
                ? "--ravencoin.sync.enabled=true" : arg).toArray(String[]::new);
        var failure = assertThrows(RuntimeException.class, () -> app.run(args));
        Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause.getMessage().contains("Legacy RVN database"));
        assertFalse(java.nio.file.Files.exists(hot));
        assertEquals("untouched legacy metadata", java.nio.file.Files.readString(cold.resolve("headers/CURRENT")));
    }
}
