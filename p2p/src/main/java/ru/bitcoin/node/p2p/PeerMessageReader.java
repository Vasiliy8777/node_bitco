package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.BitcoinMessage;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public final class PeerMessageReader
        implements AutoCloseable {

    private final Peer peer;
    private final PeerMessageDispatcher dispatcher;

    private Thread thread;

    private boolean started;
    private boolean closed;

    public PeerMessageReader(
            Peer peer,
            PeerMessageDispatcher dispatcher
    ) {
        this.peer =
                Objects.requireNonNull(
                        peer,
                        "peer"
                );

        this.dispatcher =
                Objects.requireNonNull(
                        dispatcher,
                        "dispatcher"
                );
    }

    public synchronized void start() {

        if (closed) {
            throw new IllegalStateException(
                    "Peer message reader is closed"
            );
        }

        if (started) {
            throw new IllegalStateException(
                    "Peer message reader has already been started"
            );
        }

        if (!peer.isReady()) {
            throw new IllegalStateException(
                    "Peer handshake is not complete"
            );
        }

        started = true;

        thread =
                new Thread(
                        this::run,
                        "bitcoin-peer-reader"
                );

        thread.setDaemon(
                true
        );

        thread.start();
    }

    private void run() {
        try {
            while (!isClosed()) {

                Optional<BitcoinMessage> optional =
                        peer.receive();

                if (optional.isEmpty()) {
                    failPeer(
                            new IOException(
                                    "Peer disconnected"
                            )
                    );

                    return;
                }

                dispatcher.dispatch(
                        optional.orElseThrow()
                );
            }

        } catch (IOException exception) {

            if (!isClosed()) {
                failPeer(
                        exception
                );
            }

        } catch (RuntimeException exception) {

            if (!isClosed()) {
                failPeer(
                        new IOException(
                                "Peer message reader failed",
                                exception
                        )
                );
            }
        }
    }

    public void readNext()
            throws IOException {

        synchronized (this) {
            if (started) {
                throw new IllegalStateException(
                        "Background peer message reader has already been started"
                );
            }

            if (closed) {
                throw new IllegalStateException(
                        "Peer message reader is closed"
                );
            }
        }

        Optional<BitcoinMessage> optional =
                peer.receive();

        if (optional.isEmpty()) {
            throw new IOException(
                    "Peer disconnected"
            );
        }

        dispatcher.dispatch(
                optional.orElseThrow()
        );
    }

    private void failPeer(
            IOException failure
    ) {
        peer.handleReaderFailure(
                failure
        );
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isClosedState() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}