package ru.bitcoin.node.rvn.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import ru.bitcoin.node.rvn.app.config.RvnMiningSettings;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RvnMiningAddressBindingTest {
    @Test
    void namedEnvironmentPlaceholderReachesPayoutWithoutStartingBitcoinOrMining() {
        var application = new SpringApplication(RavencoinNodeApplication.class);
        // Public Core burn-address vector only; never the user's actual environment value.
        String vector = "RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV";
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "rvn-test-environment", Map.of("RVN_MINING_ADDRESS", vector)));
        application.setEnvironment(environment);
        try (var context = application.run("--spring.main.banner-mode=off", "--logging.file.name=", "--ravencoin.network=mainnet",
                "--ravencoin.sync.enabled=false", "--ravencoin.mining.enabled=false",
                "--ravencoin.pow.self-test-enabled=false", "--ravencoin.p2p.probe-enabled=false",
                "--ravencoin.p2p.session-enabled=false")) {
            var settings = context.getBean(RvnMiningSettings.class);
            assertTrue(settings.payout().isPresent());
            assertEquals(25, settings.requirePayout().scriptPubKey().length);
            var coinbase = ru.bitcoin.node.rvn.chain.RvnCoinbase.create(4_200_000, 1, 0, settings.requirePayout());
            assertArrayEquals(settings.requirePayout().scriptPubKey(), coinbase.outputs().getFirst().scriptPubKey());
            assertFalse(settings.toString().contains(vector));
            assertFalse(context.containsBean("gpuMinerProcess"));
        }
        var empty = new RvnMiningSettings(ru.bitcoin.node.rvn.protocol.RvnNetwork.MAINNET, "");
        assertThrows(IllegalStateException.class, empty::requirePayout);
    }
}
