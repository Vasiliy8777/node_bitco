package ru.bitcoin.node.p2p.address;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Network-group key used to diversify long-lived outbound connections.
 *
 * <p>Without an AS map this follows Bitcoin Core's broad legacy grouping
 * principle: IPv4 peers are grouped by /16 and IPv6 peers by /32. Local,
 * loopback, link-local and site-local addresses are deliberately not
 * diversity-constrained so regtest/private-network deployments can keep
 * multiple local peers.
 */
public record PeerNetGroup(String value) {

    public PeerNetGroup {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static PeerNetGroup of(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        return of(address.address());
    }

    public static PeerNetGroup of(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] bytes = address.getAddress();

        if (!isDiversifiable(address)) {
            return new PeerNetGroup("local:" + address.getHostAddress());
        }

        if (address instanceof Inet4Address) {
            return new PeerNetGroup(
                    "ipv4:" + Byte.toUnsignedInt(bytes[0]) + "." + Byte.toUnsignedInt(bytes[1])
            );
        }

        if (address instanceof Inet6Address) {
            return new PeerNetGroup(
                    "ipv6:" + HexFormat.of().formatHex(bytes, 0, 4)
            );
        }

        return new PeerNetGroup("other:" + address.getHostAddress());
    }

    public static boolean isDiversifiable(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        return isDiversifiable(address.address());
    }

    private static boolean isDiversifiable(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }
}
