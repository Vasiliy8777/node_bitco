package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface BlockDownloadSession
        extends AutoCloseable {

    void submit(
            List<Hash256> blockHashes
    ) throws IOException;

    /**
     * Submit requests with validated block heights. Implementations that do not
     * need height-aware peer selection may fall back to the legacy hash-only
     * submission path.
     */
    default void submitRequests(
            List<BlockDownloadRequest> requests
    ) throws IOException {
        submit(
                requests.stream()
                        .map(BlockDownloadRequest::blockHash)
                        .toList()
        );
    }

    CompletedBlockDownload awaitCompleted()
            throws IOException;

    Optional<CompletedBlockDownload> pollCompleted(
            Duration timeout
    ) throws IOException;

    int pendingCount();

    Optional<Peer> inFlightPeer(
            Hash256 blockHash
    );

    void failPeer(
            Peer peer,
            IOException failure
    ) throws IOException;

    @Override
    void close();
}