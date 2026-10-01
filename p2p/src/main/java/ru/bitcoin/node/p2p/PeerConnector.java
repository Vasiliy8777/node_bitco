package ru.bitcoin.node.p2p;

import java.io.IOException;

@FunctionalInterface
public interface PeerConnector {

    Peer connect(
            String host,
            int port,
            int startHeight
    ) throws IOException;

    /** Managed connections defer application reads until PeerManager has attached its listeners. */
    default Peer connectManaged(String host, int port, int startHeight) throws IOException {
        return connect(host, port, startHeight);
    }

    default Peer connectManaged(String host, int port, int startHeight, PeerConnectionRole role) throws IOException {
        return connectManaged(host, port, startHeight);
    }

    /**
     * Cancels connection attempts that have not yet been returned to the caller.
     * Implementations that do not own cancellable connection resources may keep
     * the default no-op behaviour.
     */
    default void cancelPendingConnections() {
        // Optional capability.
    }
}
