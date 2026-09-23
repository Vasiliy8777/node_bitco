package ru.bitcoin.node.p2p.address;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

final class AddrManHasher {

    private AddrManHasher() {
    }

    static long hash64(
            byte[] secretKey,
            byte[]... parts
    ) {

        Objects.requireNonNull(
                secretKey,
                "secretKey"
        );

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            digest.update(
                    secretKey
            );

            for (byte[] part : parts) {

                digest.update(
                        Objects.requireNonNull(
                                part,
                                "part"
                        )
                );
            }

            byte[] first =
                    digest.digest();

            byte[] second =
                    digest.digest(
                            first
                    );

            return ByteBuffer
                    .wrap(
                            Arrays.copyOf(
                                    second,
                                    Long.BYTES
                            )
                    )
                    .getLong();

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    exception
            );
        }
    }

    static byte[] endpointBytes(
            PeerAddress address
    ) {

        Objects.requireNonNull(
                address,
                "address"
        );

        byte[] ip =
                address.address()
                        .getAddress();

        ByteBuffer buffer =
                ByteBuffer.allocate(
                        1
                                + ip.length
                                + Integer.BYTES
                );

        buffer.put(
                (byte) ip.length
        );

        buffer.put(
                ip
        );

        buffer.putInt(
                address.port()
        );

        return buffer.array();
    }

    static byte[] groupBytes(
            PeerAddress address
    ) {

        Objects.requireNonNull(
                address,
                "address"
        );

        return groupBytes(
                address.address()
        );
    }

    static byte[] groupBytes(
            PeerAddressSource source
    ) {

        Objects.requireNonNull(
                source,
                "source"
        );

        return groupBytes(
                source.address()
        );
    }

    private static byte[] groupBytes(
            java.net.InetAddress address
    ) {

        byte[] raw =
                address.getAddress();

        /*
         * Current project is still IP-only.
         *
         * IPv4 group: /16.
         * IPv6 group: /32.
         *
         * Later the network-address abstraction will replace
         * this with Core-compatible NetGroup behavior for all
         * supported networks.
         */
        if (raw.length == 4) {

            return new byte[]{
                    4,
                    raw[0],
                    raw[1]
            };
        }

        if (raw.length == 16) {

            return new byte[]{
                    6,
                    raw[0],
                    raw[1],
                    raw[2],
                    raw[3]
            };
        }

        throw new IllegalArgumentException(
                "Unsupported IP address length: "
                        + raw.length
        );
    }

    static byte[] intBytes(
            int value
    ) {

        return ByteBuffer
                .allocate(
                        Integer.BYTES
                )
                .putInt(
                        value
                )
                .array();
    }
}