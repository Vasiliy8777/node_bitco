package ru.bitcoin.node.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.Locale;

@Configuration
public class NetworkConfiguration {

    @Bean
    public NetworkParameters networkParameters(
            @Value("${bitcoin.network:regtest}")
            String networkName
    ) {
        BitcoinNetwork network;

        try {
            network =
                    BitcoinNetwork.valueOf(
                            networkName
                                    .trim()
                                    .toUpperCase(Locale.ROOT)
                    );

        } catch (IllegalArgumentException e) {

            throw new IllegalArgumentException(
                    "Unsupported Bitcoin network: "
                            + networkName,
                    e
            );
        }

        return NetworkParametersRegistry
                .forNetwork(network);
    }
}