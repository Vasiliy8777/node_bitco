package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;

import java.util.Objects;

/**
 * A block-body download request whose height is already known from the
 * validated header chain.
 *
 * <p>The height is carried into the P2P scheduler so NODE_NETWORK_LIMITED
 * peers are never asked for history outside the range they advertise.
 */
public record BlockDownloadRequest(Hash256 blockHash, long height) {

    public BlockDownloadRequest {
        Objects.requireNonNull(blockHash, "blockHash");
        if (height < 0) {
            throw new IllegalArgumentException("height must not be negative");
        }
    }
}
