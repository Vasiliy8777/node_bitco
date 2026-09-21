package ru.bitcoin.node.p2p;

import java.io.IOException;

@FunctionalInterface
public interface PeerCloseListener {

    void onPeerClosed(
            Peer peer,
            IOException cause
    );
}