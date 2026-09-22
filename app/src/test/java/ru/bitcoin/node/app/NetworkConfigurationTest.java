package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.NetworkConfiguration;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkConfigurationTest {

    @Test
    void shouldConfigureRegtest() {

        try (var context =
                     new AnnotationConfigApplicationContext()) {

            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "network-test",
                                    Map.of(
                                            "bitcoin.network",
                                            "regtest"
                                    )
                            )
                    );

            context.register(
                    NetworkConfiguration.class
            );

            context.refresh();

            NetworkParameters networkParameters =
                    context.getBean(
                            NetworkParameters.class
                    );

            assertEquals(
                    BitcoinNetwork.REGTEST,
                    networkParameters.network()
            );

            assertEquals(
                    18444,
                    networkParameters.defaultPort()
            );
        }
    }
}