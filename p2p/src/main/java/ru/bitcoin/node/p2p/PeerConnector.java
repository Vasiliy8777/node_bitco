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
}
