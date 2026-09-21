package ru.bitcoin.node.app.config;

import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ConfiguredPeerLoader {

    private final NetworkParameters networkParameters;
    private final PeerAddressManager addressManager;

    public ConfiguredPeerLoader(
            NetworkParameters networkParameters,
            PeerAddressManager addressManager
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
    }

    public void load(
            List<String> configuredPeers
    ) {

        Objects.requireNonNull(
                configuredPeers,
                "configuredPeers"
        );

        Instant seenAt =
                Instant.now();

        for (String configuredPeer : configuredPeers) {

            PeerAddress peerAddress =
                    parse(
                            configuredPeer
                    );

            addressManager.add(
                    peerAddress,
                    seenAt
            );
        }
    }

    private PeerAddress parse(
            String value
    ) {

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    "Configured peer must not be blank"
            );
        }

        String trimmed =
                value.trim();

        String host;
        int port;

        /*
         * Bracketed IPv6:
         * [::1]:18444
         */
        if (trimmed.startsWith("[")) {

            int closingBracket =
                    trimmed.indexOf(']');

            if (closingBracket < 0) {
                throw invalidPeer(
                        value
                );
            }

            host =
                    trimmed.substring(
                            1,
                            closingBracket
                    );

            if (closingBracket
                    == trimmed.length() - 1) {

                port =
                        networkParameters.defaultPort();

            } else {

                if (trimmed.charAt(
                        closingBracket + 1
                ) != ':') {

                    throw invalidPeer(
                            value
                    );
                }

                port =
                        parsePort(
                                trimmed.substring(
                                        closingBracket + 2
                                ),
                                value
                        );
            }

        } else {

            int firstColon =
                    trimmed.indexOf(':');

            int lastColon =
                    trimmed.lastIndexOf(':');

            /*
             * No colon:
             * 127.0.0.1
             * localhost
             *
             * Multiple colons:
             * unbracketed IPv6, use default port.
             */
            if (firstColon < 0
                    || firstColon != lastColon) {

                host =
                        trimmed;

                port =
                        networkParameters.defaultPort();

            } else {

                host =
                        trimmed.substring(
                                0,
                                lastColon
                        );

                port =
                        parsePort(
                                trimmed.substring(
                                        lastColon + 1
                                ),
                                value
                        );
            }
        }

        if (host.isBlank()) {
            throw invalidPeer(
                    value
            );
        }

        try {

            return new PeerAddress(
                    InetAddress.getByName(
                            host
                    ),
                    port,
                    0L
            );

        } catch (UnknownHostException exception) {

            throw new IllegalArgumentException(
                    "Unable to resolve configured peer: "
                            + value,
                    exception
            );
        }
    }

    private static int parsePort(
            String value,
            String original
    ) {

        try {

            int port =
                    Integer.parseInt(
                            value
                    );

            if (port < 1
                    || port > 65535) {

                throw invalidPeer(
                        original
                );
            }

            return port;

        } catch (NumberFormatException exception) {

            throw new IllegalArgumentException(
                    "Invalid configured peer: "
                            + original,
                    exception
            );
        }
    }

    private static IllegalArgumentException invalidPeer(
            String value
    ) {

        return new IllegalArgumentException(
                "Invalid configured peer: "
                        + value
        );
    }
}