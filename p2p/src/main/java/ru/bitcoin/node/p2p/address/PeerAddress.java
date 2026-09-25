package ru.bitcoin.node.p2p.address;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** A BIP155-capable network endpoint stored by AddrMan. */
public final class PeerAddress {
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final byte[] ONION_CHECKSUM_PREFIX = ".onion checksum".getBytes(StandardCharsets.US_ASCII);
    private static final byte TOR_V3_VERSION = 3;

    private final PeerAddressNetwork network;
    private final byte[] rawAddress;
    private final int port;
    private final long services;

    public PeerAddress(InetAddress address, int port, long services) {
        this(networkOf(Objects.requireNonNull(address, "address")), address.getAddress(), port, services);
    }

    public PeerAddress(PeerAddressNetwork network, byte[] rawAddress, int port, long services) {
        this.network = Objects.requireNonNull(network, "network");
        Objects.requireNonNull(rawAddress, "rawAddress");
        if (rawAddress.length != network.addressLength()) {
            throw new IllegalArgumentException("Invalid " + network + " address length: " + rawAddress.length);
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (services < 0) throw new IllegalArgumentException("services must fit supported uint64 range");
        if (network == PeerAddressNetwork.CJDNS && (rawAddress[0] & 0xff) != 0xfc) {
            throw new IllegalArgumentException("CJDNS address must use fc00::/8");
        }
        this.rawAddress = rawAddress.clone();
        this.port = port;
        this.services = services;
    }

    public PeerAddressNetwork network() { return network; }
    public byte[] rawAddress() { return rawAddress.clone(); }
    public int port() { return port; }
    public long services() { return services; }
    public boolean isIp() { return network == PeerAddressNetwork.IPV4 || network == PeerAddressNetwork.IPV6; }
    public boolean isLegacyAddrCompatible() { return isIp(); }
    public boolean isDirectSocketAddress() { return isIp() || network == PeerAddressNetwork.CJDNS; }

    public InetAddress address() {
        if (!isDirectSocketAddress()) {
            throw new IllegalStateException(network + " endpoint is not an InetAddress");
        }
        try { return InetAddress.getByAddress(rawAddress); }
        catch (UnknownHostException e) { throw new IllegalStateException("Validated address length became invalid", e); }
    }

    public String hostAddress() {
        return switch (network) {
            case IPV4, IPV6, CJDNS -> address().getHostAddress();
            case TORV3 -> torV3Host(rawAddress);
            case I2P -> base32(rawAddress) + ".b32.i2p";
        };
    }

    private static PeerAddressNetwork networkOf(InetAddress address) {
        if (address instanceof Inet4Address) return PeerAddressNetwork.IPV4;
        if (address instanceof Inet6Address) return PeerAddressNetwork.IPV6;
        throw new IllegalArgumentException("Unsupported InetAddress type: " + address.getClass().getName());
    }

    private static String torV3Host(byte[] pubkey) {
        try {
            MessageDigest sha3 = MessageDigest.getInstance("SHA3-256");
            sha3.update(ONION_CHECKSUM_PREFIX);
            sha3.update(pubkey);
            sha3.update(TOR_V3_VERSION);
            byte[] checksum = sha3.digest();
            byte[] encoded = Arrays.copyOf(pubkey, 35);
            encoded[32] = checksum[0]; encoded[33] = checksum[1]; encoded[34] = TOR_V3_VERSION;
            return base32(encoded) + ".onion";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 unavailable", e);
        }
    }

    private static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { out.append(BASE32[(buffer >>> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerAddress other)) return false;
        return port == other.port && services == other.services && network == other.network && Arrays.equals(rawAddress, other.rawAddress);
    }
    @Override public int hashCode() { int r = Objects.hash(network, port, services); return 31 * r + Arrays.hashCode(rawAddress); }
    @Override public String toString() { return hostAddress() + ":" + port; }
}
