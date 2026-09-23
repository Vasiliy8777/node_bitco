package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PeerManager
        implements AutoCloseable {

    private final List<Peer> peers =
            new ArrayList<>();
    private final List<java.util.function.Consumer<Peer>> listeners = new ArrayList<>();

    /** Callbacks only attach nonblocking observers; called under the manager lock. */
    public synchronized void addPeerListener(java.util.function.Consumer<Peer> listener) {
        listeners.add(Objects.requireNonNull(listener));
        peers.forEach(listener);
    }

    public synchronized void removePeerListener(java.util.function.Consumer<Peer> listener) {
        listeners.remove(listener);
    }

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

        /*
         * IMPORTANT:
         *
         * Add the peer before registering
         * the close listener.
         *
         * Peer.addCloseListener() immediately
         * reports an already CLOSED peer.
         *
         * Therefore this ordering closes the race:
         *
         * peer closes
         *      ↓
         * PeerManager.add(peer)
         *      ↓
         * add to collection
         *      ↓
         * addCloseListener()
         *      ↓
         * immediate close callback
         *      ↓
         * peer removed again
         */
        peers.add(
                peer
        );

        peer.addCloseListener(
                this::onPeerClosed
        );
        if (peers.contains(peer)) listeners.forEach(listener -> listener.accept(peer));
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

        peers.remove(
                peer
        );
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
