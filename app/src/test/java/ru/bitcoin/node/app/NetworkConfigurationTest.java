package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = BitcoinNodeApplication.class,
        properties = {
                "bitcoin.network=regtest"
        }
)
class NetworkConfigurationTest {

    @Autowired
    private NetworkParameters networkParameters;

    @Test
    void shouldConfigureRegtest() {

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