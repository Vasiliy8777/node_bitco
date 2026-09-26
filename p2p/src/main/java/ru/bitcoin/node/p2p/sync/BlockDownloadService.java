package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class BlockDownloadService {

    private final PeerManager peerManager;

    public BlockDownloadService(
            PeerManager peerManager
    ) {
        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );
    }

    public Block download(
            Hash256 blockHash
    ) throws IOException {

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        List<Peer> peers =
                peerManager.readyPeers();

        if (peers.isEmpty()) {
            throw new IOException(
                    "No ready peers available for block "
                            + blockHash.toDisplayHex()
            );
        }

        IOException failure =
                new IOException(
                        "Unable to download block "
                                + blockHash.toDisplayHex()
                                + " from "
                                + peers.size()
                                + " ready peer(s)"
                );

        for (Peer peer : peers) {

            try {
                return download(
                        peer,
                        blockHash
                );

            } catch (IOException exception) {
                failure.addSuppressed(
                        exception
                );
            }
        }

        throw failure;
    }

    public Block download(
            Peer peer,
            Hash256 blockHash
    ) throws IOException {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        if (!peer.isReady()) {
            throw new IOException(
                    "Peer is not ready for block "
                            + blockHash.toDisplayHex()
            );
        }

        try {
            return new BlockSynchronizer(
                    peer
            ).download(
                    blockHash
            );

        } catch (BlockNotFoundException exception) {

            /*
             * NOTFOUND is a valid response.
             * The peer remains healthy.
             */
            throw exception;

        } catch (IOException exception) {

            /*
             * Transport/protocol failure makes this peer
             * unavailable for subsequent downloads.
             */
            try {
                peer.close();
            } catch (IOException closeException) {
                exception.addSuppressed(
                        closeException
                );
            } finally {
                /*
                 * The background reader owns the original CLOSED transition.
                 * Its dispatcher is failed before Peer close-listeners are
                 * notified, so this download thread can wake up while the
                 * PeerManager close callback is still pending.
                 *
                 * Remove synchronously here as well. PeerManager.remove() is
                 * idempotent, therefore the eventual close callback remains
                 * safe. This guarantees that a failed download never returns
                 * or retries with a CLOSED peer still exposed by the manager.
                 */
                peerManager.remove(
                        peer
                );
            }

            throw exception;
        }
    }
}