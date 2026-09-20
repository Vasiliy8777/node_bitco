package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;

import java.io.IOException;
import java.util.List;

public interface BlockDownloadSession
        extends AutoCloseable {

    void submit(
            List<Hash256> blockHashes
    ) throws IOException;

    CompletedBlockDownload awaitCompleted()
            throws IOException;

    int pendingCount();

    @Override
    void close();
}