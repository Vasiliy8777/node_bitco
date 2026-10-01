package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BitcoinClient
        implements PeerConnector {

    private static final System.Logger log =
            System.getLogger(BitcoinClient.class.getName());

    private final NetworkParameters networkParameters;
    private final long localServices;
    private final boolean relay;
    private final PeerTransportPolicy transportPolicy;
    private final Set<Peer> pendingConnections = ConcurrentHashMap.newKeySet();

    public BitcoinClient(
            NetworkParameters networkParameters
    ) {
        this(
                networkParameters,
                VersionMessage.DEFAULT_SERVICES,
                true,
                false
        );
    }

    public BitcoinClient(NetworkParameters networkParameters, long localServices, boolean relay) {
        this(networkParameters, localServices, relay, false);
    }

    public BitcoinClient(
            NetworkParameters networkParameters,
            long localServices,
            boolean relay,
            boolean v2Transport
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
        this.transportPolicy = v2Transport ? PeerTransportPolicy.AUTO : PeerTransportPolicy.V1_ONLY;
    }
    @Override
    public Peer connect(
            String host,
            int port,
            int startHeight
    ) throws IOException {

        return connect(host, port, startHeight, true, relay, transportPolicy);
    }

    @Override public Peer connectManaged(String host, int port, int startHeight) throws IOException {
        return connect(host, port, startHeight, false, relay, transportPolicy);
    }

    @Override
    public Peer connectManaged(String host, int port, int startHeight, PeerConnectionRole role) throws IOException {
        Objects.requireNonNull(role, "role");
        boolean connectionRelay = role == PeerConnectionRole.BLOCK_RELAY_ONLY ? false : relay;
        return connect(host, port, startHeight, false, connectionRelay, transportPolicy);
    }

    private Peer connect(String host, int port, int startHeight, boolean startReader) throws IOException {
        return connect(host, port, startHeight, startReader, relay, transportPolicy);
    }

    private Peer connect(String host, int port, int startHeight, boolean startReader, boolean connectionRelay) throws IOException {
        return connect(host, port, startHeight, startReader, connectionRelay, transportPolicy);
    }

    /** Connect with an explicit per-connection transport policy. */
    public Peer connect(String host, int port, int startHeight, PeerTransportPolicy policy) throws IOException {
        return connect(host, port, startHeight, true, relay, Objects.requireNonNull(policy, "policy"));
    }

    private Peer connect(String host, int port, int startHeight, boolean startReader, boolean connectionRelay,
                         PeerTransportPolicy policy) throws IOException {

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

        Peer peer = newPeer(startHeight, connectionRelay);
        pendingConnections.add(peer);

        try {
            if (policy == PeerTransportPolicy.V1_ONLY) {
                peer.connect(host, port);
            } else {
                try {
                    peer.connectV2(host, port);
                } catch (IOException | RuntimeException v2Failure) {
                    try { peer.close(); } catch (IOException closeFailure) { v2Failure.addSuppressed(closeFailure); }
                    if (policy == PeerTransportPolicy.V2_ONLY) throw v2Failure;

                    log.log(
                            System.Logger.Level.DEBUG,
                            "BIP324 v2 connection to {0}:{1} failed; reconnecting with v1: {2}",
                            host, port, v2Failure.toString()
                    );

                    // BIP324 AUTO mode reconnects over a fresh TCP connection before downgrading to v1.
                    pendingConnections.remove(peer);
                    peer = newPeer(startHeight, connectionRelay);
                    pendingConnections.add(peer);
                    peer.markV2Fallback();
                    peer.connect(host, port);
                }
            }

            peer.handshake(startReader);

            if (!peer.isReady()) {
                throw new IOException(
                        "Peer handshake completed without READY state"
                );
            }

            pendingConnections.remove(peer);
            return peer;

        } catch (IOException | RuntimeException exception) {

            pendingConnections.remove(peer);
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


    @Override
    public void cancelPendingConnections() {
        for (Peer peer : pendingConnections) {
            try {
                peer.close();
            } catch (IOException ignored) {
                // The connection attempt will observe the socket close.
            }
        }
    }

    private Peer newPeer(int startHeight, boolean connectionRelay) {
        return new Peer(new PeerConnection(networkParameters), localServices, startHeight, connectionRelay);
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
