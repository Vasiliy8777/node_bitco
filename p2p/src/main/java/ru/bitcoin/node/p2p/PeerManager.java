package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PeerManager
        implements AutoCloseable {

    private final List<Peer> peers =
            new ArrayList<>();

    public synchronized void add(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        if (peers.contains(peer)) {
            return;
        }

        peer.addCloseListener(
                this::onPeerClosed
        );

        peers.add(
                peer
        );
    }

    private void onPeerClosed(
            Peer peer,
            IOException cause
    ) {

        synchronized (this) {

            peers.remove(
                    peer
            );
        }
    }

    public synchronized void remove(
            Peer peer
    ) {
        Objects.requireNonNull(
                peer,
                "peer"
        );

        peers.remove(peer);
    }

    public synchronized List<Peer> peers() {
        return List.copyOf(
                peers
        );
    }

    public synchronized List<Peer> readyPeers() {
        return peers.stream()
                .filter(Peer::isReady)
                .toList();
    }

    public synchronized int size() {
        return peers.size();
    }

    public synchronized boolean isEmpty() {
        return peers.isEmpty();
    }

    @Override
    public void close()
            throws IOException {

        List<Peer> snapshot;

        synchronized (this) {
            snapshot =
                    List.copyOf(
                            peers
                    );

            peers.clear();
        }

        IOException failure =
                null;

        for (Peer peer : snapshot) {
            try {
                peer.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure =
                            new IOException(
                                    "Failed to close one or more peers"
                            );
                }

                failure.addSuppressed(
                        exception
                );
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}