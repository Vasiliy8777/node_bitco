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

    /** Whether another idle peer could download a block beyond a full window. */
    default boolean hasIdlePeerFor(BlockDownloadRequest request, Peer blockingPeer) {
        return false;
    }

    /** Evaluate FindNextBlocks in the context of each idle peer's own chain and window. */
    default Optional<Peer> findWindowStaller(BlockDownloadRequest beyondWindow,
            java.util.function.BiFunction<Peer, BlockDownloadPeerPolicy, Optional<Peer>> probe) {
        return Optional.empty();
    }

    void failPeer(
            Peer peer,
            IOException failure
    ) throws IOException;

    /**
     * Release only the supplied frontier block from its current peer so it can
     * be retried on another READY peer without tearing down the whole
     * connection. Returns true when the expected peer owned the block.
     */
    boolean retryBlock(
            Hash256 blockHash,
            Peer expectedPeer,
            IOException failure
    ) throws IOException;

    @Override
    void close();
}
