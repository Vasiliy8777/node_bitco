package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerMessageDispatcher;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HeaderSynchronizer {

    public static final Duration DEFAULT_RESPONSE_TIMEOUT =
            Duration.ofSeconds(
                    120
            );

    private final Peer peer;
    private final Duration responseTimeout;
    public interface ResponseControl {
        boolean trySend(GetHeadersMessage request) throws IOException;
        void waiting() throws IOException;
        void validated();
    }
    private final ResponseControl control;

    public Peer peer() { return peer; }

    public HeaderSynchronizer(
            Peer peer
    ) {
        this(
                peer,
                DEFAULT_RESPONSE_TIMEOUT
        );
    }

    public HeaderSynchronizer(
            Peer peer,
            Duration responseTimeout
    ) {
        this(peer, responseTimeout, null);
    }
    public HeaderSynchronizer(Peer peer, Duration responseTimeout, ResponseControl control) {
        this.control = control;
        this.peer =
                Objects.requireNonNull(
                        peer,
                        "peer"
                );

        this.responseTimeout =
                Objects.requireNonNull(
                        responseTimeout,
                        "responseTimeout"
                );

        if (responseTimeout.isZero()
                || responseTimeout.isNegative()) {

            throw new IllegalArgumentException(
                    "responseTimeout must be positive"
            );
        }
    }

    public HeadersMessage download(
            List<Hash256> locatorHashes,
            Hash256 stopHash
    ) throws IOException {

        Objects.requireNonNull(
                locatorHashes,
                "locatorHashes"
        );

        Objects.requireNonNull(
                stopHash,
                "stopHash"
        );

        if (!peer.isReady()) {
            if (peer.state() == ru.bitcoin.node.p2p.PeerState.CLOSED) {
                throw new IOException("Peer disconnected before header request");
            }
            throw new IllegalStateException(
                    "Peer handshake is not complete"
            );
        }

        VersionMessage remoteVersion =
                peer.remoteVersion();

        int protocolVersion =
                Math.min(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        remoteVersion.version()
                );

        GetHeadersMessage request =
                new GetHeadersMessage(
                        protocolVersion,
                        locatorHashes,
                        stopHash
                );

        PeerMessageDispatcher dispatcher =
                peer.messageDispatcher();

        CompletableFuture<HeadersMessage> future =
                dispatcher.registerHeaders();

        try {

            long deadline = System.nanoTime() + responseTimeout.toNanos();
            boolean sent = false;
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (control != null) control.waiting();
                if (control == null && remaining <= 0) throw new HeaderSynchronizationTimeoutException(responseTimeout);
                if (!future.isDone() && !sent) sent = control == null ? peer.trySendGetHeaders(request) : control.trySend(request);
                // A response already dispatched to an asynchronous validator may
                // release the gate without completing this newer waiter. Recheck
                // admission without introducing another socket reader.
                long wait = control != null ? TimeUnit.MILLISECONDS.toNanos(100)
                        : sent ? remaining : Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100));
                try { return completedHeaders(future, Duration.ofNanos(wait)); }
                catch (HeaderSynchronizationTimeoutException timeout) {
                    if (future.isDone()) return completedHeaders(future, Duration.ofNanos(1));
                    if (control == null && (sent || System.nanoTime() >= deadline))
                        throw new HeaderSynchronizationTimeoutException(responseTimeout);
                }
            }

        } finally {

            dispatcher.unregisterHeaders(
                    future
            );
        }
    }

    /** Call after the downloaded nonempty batch validates and connects to the header tree. */
    public void headersValidated() { peer.headersResponseValidated(); if (control != null) control.validated(); }

    private static HeadersMessage completedHeaders(
            CompletableFuture<HeadersMessage> future,
            Duration timeout
    ) throws IOException {

        try {

            return future.get(
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );

        } catch (TimeoutException exception) {

            throw new HeaderSynchronizationTimeoutException(
                    timeout
            );

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IOException(
                    "Interrupted while waiting for headers",
                    exception
            );

        } catch (ExecutionException exception) {

            Throwable cause =
                    exception.getCause();

            if (cause instanceof IOException ioException) {
                throw ioException;
            }

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new IOException(
                    "Header download failed",
                    cause
            );
        }
    }
}
