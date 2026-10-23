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
        var app = application(Map.of("RVN_DATA_DIRECTORY", "./target/test-rvn-data",
                "RVN_MINING_ENABLED", "false", "RVN_SYNC_BLOCKS", "false"));
        try (var context = app.run(arguments())) {
            assertEquals("./target/test-rvn-data", context.getEnvironment().getProperty("ravencoin.data-directory"));
            var directories = context.getBean(ru.bitcoin.node.rvn.app.config.RvnDataDirectories.class);
            var root = java.nio.file.Path.of("./target/test-rvn-data").toAbsolutePath().normalize();
            assertEquals(root, directories.root());
            assertEquals(root.resolve("headers"), directories.headers());
            assertEquals(root.resolve("coins"), directories.coins());
            assertEquals(root.resolve("dag"), directories.dag());
            assertEquals(root.resolve("logs"), directories.logs());
            assertEquals(root.resolve("tmp"), directories.temporary());
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
}
