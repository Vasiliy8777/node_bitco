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
                "--ravencoin.storage.hot-directory=" + path.resolve("hot"),
                "--ravencoin.mining.enabled=false", "--ravencoin.mining.address=", "--ravencoin.pow.self-test-enabled=false",
                "--ravencoin.mining.cuda.enabled=false",
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
            assertTrue(context.getBeansOfType(ru.bitcoin.node.rvn.pow.RvnCudaKawpowWorker.class).isEmpty());
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
                assertTrue(Files.exists(path.resolve("hot/headers/RVN_HEADERS")));
                assertTrue(Files.exists(path.resolve("blocks/RVN_ARCHIVE")));
                assertFalse(context.containsBean("chainDatabase"));
                assertFalse(context.containsBean("stratumServer"));
            }
        }
        try (var files = new ru.bitcoin.node.rvn.storage.RvnFlatFileStore(path.resolve("blocks"),
                     "BLOCKS\n" + ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits.GENESIS_ID, 8_000_000);
             var reopened = new RvnHeaderStore(path.resolve("hot/headers"),
                     ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits.GENESIS_ID,
                     ru.bitcoin.node.rvn.pow.RvnHistoricalPowSelfTest.mainnetGenesisHeader(),
                     ru.bitcoin.node.rvn.consensus.RvnMainnetDifficulty.POW_LIMIT, files)) {
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
                assertTrue(Files.exists(path.resolve("hot/headers/RVN_HEADERS")));
                assertTrue(Files.exists(path.resolve("hot/coins/RVN_COINS")));
                assertTrue(Files.exists(path.resolve("undo/RVN_ARCHIVE")));
            }
        }
        var genesis = ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits.GENESIS_ID;
        try (var blocks = new ru.bitcoin.node.rvn.storage.RvnFlatFileStore(path.resolve("blocks"), "BLOCKS\n" + genesis, 8_000_000);
             var undo = new ru.bitcoin.node.rvn.storage.RvnFlatFileStore(path.resolve("undo"), "UNDO\n" + genesis, 64 * 1024 * 1024);
             var headers = new RvnHeaderStore(path.resolve("hot/headers"), genesis,
                     ru.bitcoin.node.rvn.pow.RvnHistoricalPowSelfTest.mainnetGenesisHeader(),
                     ru.bitcoin.node.rvn.consensus.RvnMainnetDifficulty.POW_LIMIT, blocks);
             var coins = new ru.bitcoin.node.rvn.storage.RvnCoinStore(path.resolve("hot/coins"), genesis, undo)) {
            assertEquals(headers.best().id(), coins.tip().id());
        }
    }
}
