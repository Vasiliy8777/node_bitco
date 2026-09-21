package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public final class SystemDnsResolver
        implements DnsResolver {

    @Override
    public List<InetAddress> resolve(
            String host
    ) throws UnknownHostException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "host must not be blank"
            );
        }

        return List.copyOf(
                Arrays.asList(
                        InetAddress.getAllByName(
                                host
                        )
                )
        );
    }
}