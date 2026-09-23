package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.IOException;
import java.util.Objects;

public final class BitcoinClient
        implements PeerConnector {

    private final NetworkParameters networkParameters;
    private final long localServices;
    private final boolean relay;

    public BitcoinClient(
            NetworkParameters networkParameters
    ) {
        this(
                networkParameters,
                VersionMessage.DEFAULT_SERVICES,
                true
        );
    }

    public BitcoinClient(
            NetworkParameters networkParameters,
            long localServices,
            boolean relay
    ) {
        this.networkParameters =
                Objects.requireNonNull(
                        networkParameters,
                        "networkParameters"
                );

        this.localServices =
                localServices;

        this.relay =
                relay;
    }
    @Override
    public Peer connect(
            String host,
            int port,
            int startHeight
    ) throws IOException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "host must not be blank"
            );
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port
            );
        }

        if (startHeight < 0) {
            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        Peer peer =
                new Peer(
                        new PeerConnection(
                                networkParameters
                        ),
                        localServices,
                        startHeight,
                        relay
                );

        try {

            peer.connect(
                    host,
                    port
            );

            peer.handshake();

            if (!peer.isReady()) {
                throw new IOException(
                        "Peer handshake completed without READY state"
                );
            }

            return peer;

        } catch (IOException | RuntimeException exception) {

            try {
                peer.close();
            } catch (IOException closeException) {
                exception.addSuppressed(
                        closeException
                );
            }

            throw exception;
        }
    }

    public Peer connect(
            String host,
            int startHeight
    ) throws IOException {

        return connect(
                host,
                networkParameters.defaultPort(),
                startHeight
        );
    }
}