package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface DnsResolver {

    List<InetAddress> resolve(
            String host
    ) throws UnknownHostException;
}