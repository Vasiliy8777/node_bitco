package ru.bitcoin.node.rvn.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService;
import ru.bitcoin.node.rvn.storage.RvnHeaderStore;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnStartupIsolationTest {
    @TempDir Path temp;

    private String[] arguments(Path path, String... extra) {
        List<String> args = new ArrayList<>(List.of("--spring.main.banner-mode=off", "--logging.file.name=",
                "--ravencoin.network=mainnet", "--ravencoin.data-directory=" + path,
                "--ravencoin.mining.enabled=false", "--ravencoin.mining.address=", "--ravencoin.pow.self-test-enabled=false",
                "--ravencoin.p2p.probe-enabled=false", "--ravencoin.p2p.session-enabled=false"));
        args.addAll(List.of(extra)); return args.toArray(String[]::new);
    }

    @Test
    void offlineStartupDoesNotCreateRuntimeDatabase() {
        Path path = temp.resolve("offline");
        try (var context = SpringApplication.run(RavencoinNodeApplication.class,
                arguments(path, "--ravencoin.sync.enabled=false"))) {
            assertTrue(context.getBeansOfType(RvnHeaderStore.class).isEmpty());
            assertFalse(Files.exists(path));
            assertFalse(context.containsBean("chainDatabase"));
            assertFalse(context.containsBean("gpuMinerProcess"));
        }
    }

    @Test
    void enabledSyncOwnsIndependentDatabaseAndShutdownReleasesIt() throws Exception {
        Path path = temp.resolve("mainnet");
        // A local listening socket prevents any connection to public peers in this test.
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try (var context = SpringApplication.run(RavencoinNodeApplication.class,
                    arguments(path, "--ravencoin.sync.enabled=true", "--ravencoin.sync.peers=127.0.0.1",
                            "--ravencoin.p2p.port=" + server.getLocalPort(), "--ravencoin.p2p.timeout-ms=300"))) {
                assertEquals(0, context.getBean(RvnHeaderStore.class).best().height());
                assertTrue(context.getBean(RvnHeaderSyncService.class).isRunning());
                assertTrue(Files.exists(path.resolve("headers/RVN_HEADERS")));
                assertFalse(context.containsBean("chainDatabase"));
                assertFalse(context.containsBean("stratumServer"));
            }
        }
        try (var reopened = ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.open(path.resolve("headers"))) {
            assertEquals(0, reopened.best().height());
        }
    }

    @Test
    void nativeReplayOpensBothStoresUnderConfiguredRootAndReleasesThem() throws Exception {
        Path path = temp.resolve("replay");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try (var context = SpringApplication.run(RavencoinNodeApplication.class,
                    arguments(path, "--ravencoin.sync.enabled=true", "--ravencoin.sync.blocks-enabled=true",
                            "--ravencoin.validation.native-replay-enabled=true", "--ravencoin.sync.peers=127.0.0.1",
                            "--ravencoin.p2p.port=" + server.getLocalPort(), "--ravencoin.p2p.timeout-ms=300"))) {
                assertNotNull(context.getBean(ru.bitcoin.node.rvn.app.chain.RvnNativeBlockReplay.class));
                assertEquals(0, context.getBean(ru.bitcoin.node.rvn.storage.RvnCoinStore.class).tip().height());
                assertTrue(Files.exists(path.resolve("headers/RVN_HEADERS")));
                assertTrue(Files.exists(path.resolve("coins/RVN_COINS")));
            }
        }
        try (var headers = ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.open(path.resolve("headers"));
             var coins = new ru.bitcoin.node.rvn.storage.RvnCoinStore(path.resolve("coins"),
                     ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits.GENESIS_ID)) {
            assertEquals(headers.best().id(), coins.tip().id());
        }
    }
}
