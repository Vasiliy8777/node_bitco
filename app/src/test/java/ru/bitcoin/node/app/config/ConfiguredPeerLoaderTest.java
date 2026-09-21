package ru.bitcoin.node.app.config;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.KnownPeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfiguredPeerLoaderTest {

    @Test
    void shouldLoadConfiguredIpv4Peer()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        PeerAddressManager addressManager =
                new PeerAddressManager();

        ConfiguredPeerLoader loader =
                new ConfiguredPeerLoader(
                        parameters,
                        addressManager
                );

        loader.load(
                List.of(
                        "127.0.0.1:18444"
                )
        );

        assertEquals(
                1,
                addressManager.size()
        );

        KnownPeerAddress known =
                addressManager
                        .addresses()
                        .getFirst();

        assertEquals(
                "127.0.0.1",
                known.peerAddress()
                        .hostAddress()
        );

        assertEquals(
                18444,
                known.peerAddress()
                        .port()
        );
    }

    @Test
    void shouldUseNetworkDefaultPortWhenPortIsMissing()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        PeerAddressManager addressManager =
                new PeerAddressManager();

        ConfiguredPeerLoader loader =
                new ConfiguredPeerLoader(
                        parameters,
                        addressManager
                );

        loader.load(
                List.of(
                        "127.0.0.1"
                )
        );

        assertEquals(
                parameters.defaultPort(),
                addressManager
                        .addresses()
                        .getFirst()
                        .peerAddress()
                        .port()
        );
    }

    @Test
    void shouldRejectInvalidPort() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        PeerAddressManager addressManager =
                new PeerAddressManager();

        ConfiguredPeerLoader loader =
                new ConfiguredPeerLoader(
                        parameters,
                        addressManager
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(
                        List.of(
                                "127.0.0.1:70000"
                        )
                )
        );
    }
}