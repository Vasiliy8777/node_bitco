package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.HexFormat;
import java.util.Objects;

/** Network-group key used to diversify long-lived outbound connections. */
public record PeerNetGroup(String value) {
    public PeerNetGroup { Objects.requireNonNull(value, "value"); if (value.isBlank()) throw new IllegalArgumentException("value must not be blank"); }

    public static PeerNetGroup of(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] raw = address.rawAddress();
        if (address.isIp() && !isDiversifiableIp(address.address())) return new PeerNetGroup("local:" + address.hostAddress());
        int prefix = switch (address.network()) { case IPV4 -> 2; case IPV6, CJDNS, TORV3, I2P -> 4; };
        return new PeerNetGroup(address.network().name().toLowerCase() + ":" + HexFormat.of().formatHex(raw, 0, prefix));
    }

    public static PeerNetGroup of(InetAddress address) { return of(new PeerAddress(address, 1, 0L)); }

    public static boolean isDiversifiable(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        return !address.isIp() || isDiversifiableIp(address.address());
    }

    private static boolean isDiversifiableIp(InetAddress address) {
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress() && !address.isMulticastAddress();
    }
}
