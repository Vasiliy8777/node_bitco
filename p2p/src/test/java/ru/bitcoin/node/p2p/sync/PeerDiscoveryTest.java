package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.DnsResolver;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PeerDiscoveryTest {

    private static final Instant NOW =
            Instant.ofEpochSecond(
                    1_700_000_000L
            );

    @Test
    void shouldDiscoverDnsAddressesAndPopulateManager()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        PeerAddressManager manager =
                new PeerAddressManager();

        DnsResolver resolver =
                host -> {

                    if (host.equals(
                            "dnsseed.bluematt.me"
                    )) {
                        return List.of(
                                InetAddress.getByName(
                                        "192.0.2.10"
                                ),
                                InetAddress.getByName(
                                        "192.0.2.11"
                                )
                        );
                    }

                    return List.of();
                };

        PeerDiscovery discovery =
                new PeerDiscovery(
                        parameters,
                        manager,
                        resolver,
                        () -> NOW
                );

        List<PeerAddress> discovered =
                discovery.discover();

        assertEquals(
                2,
                discovered.size()
        );

        assertEquals(
                2,
                manager.size()
        );

        for (PeerAddress address :
                discovered) {

            assertEquals(
                    parameters.defaultPort(),
                    address.port()
            );

            assertEquals(
                    0L,
                    address.services()
            );

            assertEquals(
                    NOW,
                    manager.find(address)
                            .orElseThrow()
                            .firstSeen()
            );
        }
    }

    @Test
    void shouldDeduplicateSameEndpointReturnedByDifferentSeeds()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        PeerAddressManager manager =
                new PeerAddressManager();

        InetAddress sameAddress =
                InetAddress.getByName(
                        "192.0.2.20"
                );

        DnsResolver resolver =
                host -> List.of(
                        sameAddress
                );

        PeerDiscovery discovery =
                new PeerDiscovery(
                        parameters,
                        manager,
                        resolver,
                        () -> NOW
                );

        List<PeerAddress> discovered =
                discovery.discover();

        assertFalse(
                discovered.isEmpty()
        );

        /*
         * discover() reports resolver results,
         * while manager owns the deduplicated set.
         */
        assertEquals(
                1,
                manager.size()
        );
    }

    @Test
    void shouldContinueAfterOneDnsSeedFails()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        PeerAddressManager manager =
                new PeerAddressManager();

        DnsResolver resolver =
                host -> {

                    if (host.equals(
                            "dnsseed.bluematt.me"
                    )) {
                        throw new UnknownHostException(
                                host
                        );
                    }

                    if (host.equals(
                            "seed.bitcoin.jonasschnelli.ch"
                    )) {
                        return List.of(
                                InetAddress.getByName(
                                        "192.0.2.30"
                                )
                        );
                    }

                    return List.of();
                };

        PeerDiscovery discovery =
                new PeerDiscovery(
                        parameters,
                        manager,
                        resolver,
                        () -> NOW
                );

        List<PeerAddress> discovered =
                discovery.discover();

        assertEquals(
                1,
                discovered.size()
        );

        assertEquals(
                1,
                manager.size()
        );

        assertEquals(
                "192.0.2.30",
                discovered.get(0)
                        .hostAddress()
        );
    }

    @Test
    void shouldUseNetworkDefaultPort()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        PeerAddressManager manager =
                new PeerAddressManager();

        DnsResolver resolver =
                host -> List.of(
                        InetAddress.getByName(
                                "192.0.2.40"
                        )
                );

        PeerDiscovery discovery =
                new PeerDiscovery(
                        parameters,
                        manager,
                        resolver,
                        () -> NOW
                );

        List<PeerAddress> discovered =
                discovery.discover();

        assertFalse(
                discovered.isEmpty()
        );

        for (PeerAddress address :
                discovered) {

            assertEquals(
                    parameters.defaultPort(),
                    address.port()
            );
        }
    }

    @Test
    void shouldNotPerformDnsLookupOnRegtest() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        PeerAddressManager manager =
                new PeerAddressManager();

        int[] calls = {0};

        DnsResolver resolver =
                host -> {
                    calls[0]++;
                    throw new AssertionError(
                            "DNS resolver must not be called on regtest"
                    );
                };

        PeerDiscovery discovery =
                new PeerDiscovery(
                        parameters,
                        manager,
                        resolver,
                        () -> NOW
                );

        List<PeerAddress> discovered =
                discovery.discover();

        assertTrue(
                discovered.isEmpty()
        );

        assertTrue(
                manager.isEmpty()
        );

        assertEquals(
                0,
                calls[0]
        );
    }
}