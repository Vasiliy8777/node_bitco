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
            }

            throw exception;
        }
    }
}