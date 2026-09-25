package ru.bitcoin.node.p2p.address;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

final class AddrManHasher {
    private AddrManHasher() {}

    static long hash64(byte[] secretKey, byte[]... parts) {
        Objects.requireNonNull(secretKey, "secretKey");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secretKey);
            for (byte[] part : parts) digest.update(Objects.requireNonNull(part, "part"));
            byte[] first = digest.digest();
            byte[] second = digest.digest(first);
            return ByteBuffer.wrap(Arrays.copyOf(second, Long.BYTES)).getLong();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static byte[] endpointBytes(PeerAddress address) {
        byte[] raw = address.rawAddress();
        ByteBuffer b = ByteBuffer.allocate(2 + raw.length + Integer.BYTES);
        b.put((byte) address.network().bip155Id()).put((byte) raw.length).put(raw).putInt(address.port());
        return b.array();
    }

    static byte[] groupBytes(PeerAddress address) {
        return groupBytes(address.network(), address.rawAddress());
    }

    static byte[] groupBytes(PeerAddressSource source) {
        return groupBytes(source.network(), source.rawAddress());
    }

    private static byte[] groupBytes(PeerAddressNetwork network, byte[] raw) {
        int prefix = switch (network) {
            case IPV4 -> 2;
            case IPV6, CJDNS -> 4;
            case TORV3, I2P -> 4;
        };
        byte[] out = new byte[2 + prefix];
        out[0] = (byte) network.bip155Id(); out[1] = (byte) prefix;
        System.arraycopy(raw, 0, out, 2, prefix);
        return out;
    }

    static byte[] intBytes(int value) { return ByteBuffer.allocate(Integer.BYTES).putInt(value).array(); }
}
