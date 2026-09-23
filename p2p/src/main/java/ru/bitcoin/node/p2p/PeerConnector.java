package ru.bitcoin.node.p2p;

import java.io.IOException;

@FunctionalInterface
public interface PeerConnector {

    Peer connect(
            String host,
            int port,
            int startHeight
    ) throws IOException;
}