package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.p2p.address.DnsResolver;
import ru.bitcoin.node.p2p.address.NetworkDnsSeeds;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.address.SystemDnsResolver;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class PeerDiscovery {

    private final NetworkParameters networkParameters;
    private final PeerAddressManager addressManager;
    private final DnsResolver dnsResolver;
    private final Supplier<Instant> clock;

    public PeerDiscovery(
            NetworkParameters networkParameters,
            PeerAddressManager addressManager
    ) {
        this(
                networkParameters,
                addressManager,
                new SystemDnsResolver(),
                Instant::now
        );
    }

    public PeerDiscovery(
            NetworkParameters networkParameters,
            PeerAddressManager addressManager,
            DnsResolver dnsResolver,
            Supplier<Instant> clock
    ) {
        this.networkParameters =
                Objects.requireNonNull(
                        networkParameters,
                        "networkParameters"
                );

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );

        this.dnsResolver =
                Objects.requireNonNull(
                        dnsResolver,
                        "dnsResolver"
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock"
                );
    }

    public List<PeerAddress> discover() {

        List<String> seeds =
                NetworkDnsSeeds.forNetwork(
                        networkParameters.network()
                );

        if (seeds.isEmpty()) {
            return List.of();
        }

        Instant discoveredAt =
                Objects.requireNonNull(
                        clock.get(),
                        "clock returned null"
                );

        List<PeerAddress> discovered =
                new ArrayList<>();

        for (String seed : seeds) {

            List<InetAddress> resolved;

            try {
                resolved =
                        dnsResolver.resolve(
                                seed
                        );
            } catch (UnknownHostException exception) {

                /*
                 * Failure of one DNS seed must not prevent
                 * discovery through the remaining seeds.
                 */
                continue;
            }

            if (resolved == null) {
                throw new IllegalStateException(
                        "DNS resolver returned null for "
                                + seed
                );
            }

            for (InetAddress address : resolved) {

                if (address == null) {
                    throw new IllegalStateException(
                            "DNS resolver returned null address for "
                                    + seed
                    );
                }

                PeerAddress peerAddress =
                        new PeerAddress(
                                address,
                                networkParameters.defaultPort(),
                                0L
                        );

                addressManager.add(
                        peerAddress,
                        discoveredAt
                );

                discovered.add(
                        peerAddress
                );
            }
        }

        return List.copyOf(
                discovered
        );
    }
}