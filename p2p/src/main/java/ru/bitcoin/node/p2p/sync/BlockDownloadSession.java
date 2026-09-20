package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface BlockDownloadSession
        extends AutoCloseable {

    void submit(
            List<Hash256> blockHashes
    ) throws IOException;

    CompletedBlockDownload awaitCompleted()
            throws IOException;

    int pendingCount();

    Optional<Peer> inFlightPeer(
            Hash256 blockHash
    );

    @Override
    void close();
}