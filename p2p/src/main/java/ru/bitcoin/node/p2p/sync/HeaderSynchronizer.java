package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerMessageDispatcher;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class HeaderSynchronizer {

    private final Peer peer;

    public HeaderSynchronizer(
            Peer peer
    ) {
        this.peer =
                Objects.requireNonNull(
                        peer,
                        "peer"
                );
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
            peer.send(
                    BitcoinMessages.getHeaders(
                            request
                    )
            );

            return completedHeaders(
                    future
            );

        } finally {
            dispatcher.unregisterHeaders(
                    future
            );
        }
    }

    private static HeadersMessage completedHeaders(
            CompletableFuture<HeadersMessage> future
    ) throws IOException {

        try {
            return future.join();

        } catch (CompletionException exception) {

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