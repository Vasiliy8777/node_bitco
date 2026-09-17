package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HeaderSynchronizer {

    private final PeerConnection connection;
    private final Peer peer;

    public HeaderSynchronizer(
            PeerConnection connection,
            Peer peer
    ) {
        this.connection =
                Objects.requireNonNull(
                        connection,
                        "connection"
                );

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

        connection.send(
                BitcoinMessages.getHeaders(
                        request
                )
        );

        while (true) {

            Optional<BitcoinMessage> optional =
                    connection.receive();

            if (optional.isEmpty()) {
                throw new IOException(
                        "Peer disconnected before sending headers"
                );
            }

            BitcoinMessage message =
                    optional.orElseThrow();

            if ("headers".equals(
                    message.command()
            )) {
                return BitcoinMessages.decodeHeaders(
                        message
                );
            }

            peer.handleMessage(
                    message
            );
        }
    }
}