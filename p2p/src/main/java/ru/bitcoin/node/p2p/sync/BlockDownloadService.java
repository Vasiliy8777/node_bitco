package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.PeerCloseException;
import ru.bitcoin.node.p2p.PeerCloseReason;

import java.io.EOFException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

    public CompletableFuture<Block> downloadAsync(
            Peer peer,
            Hash256 blockHash
    ) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(blockHash, "blockHash");

        if (!peer.isReady()) {
            return CompletableFuture.failedFuture(
                    new IOException(
                            "Peer is not ready for block "
                                    + blockHash.toDisplayHex()
                    )
            );
        }

        CompletableFuture<Block> network =
                new BlockSynchronizer(peer).downloadAsync(blockHash);

        CompletableFuture<Block> result =
                new CompletableFuture<>();

        network.whenComplete((block, failure) -> {
            if (failure == null) {
                result.complete(block);
                return;
            }

            Throwable cause = unwrap(failure);

            /*
             * Scheduler-side frontier rescue deliberately cancels a single
             * request. Cancellation is not a peer transport failure and must
             * never tear down an otherwise healthy connection.
             */
            if (cause instanceof java.util.concurrent.CancellationException) {
                result.cancel(false);
                return;
            }

            if (cause instanceof BlockNotFoundException notFound) {
                result.completeExceptionally(notFound);
                return;
            }

            if (cause instanceof IOException ioException) {
                if (isFatalTransportFailure(peer, ioException)) {
                    closeAndRemove(peer, ioException, classifyTransportFailure(ioException));
                }
                result.completeExceptionally(ioException);
                return;
            }

            if (cause instanceof RuntimeException runtimeException) {
                result.completeExceptionally(runtimeException);
                return;
            }

            IOException ioException =
                    new IOException("Block download failed", cause);
            if (!peer.isReady()) {
                closeAndRemove(peer, ioException, PeerCloseReason.LOCAL_REQUEST_FAILURE);
            }
            result.completeExceptionally(ioException);
        });

        /*
         * CompletableFuture dependent stages do not propagate cancellation
         * upstream. Explicit propagation is required so a rescued/stale GETDATA
         * unregisters its dispatcher future instead of leaking until a late
         * BLOCK or peer close arrives.
         */
        result.whenComplete((ignoredBlock, ignoredFailure) -> {
            if (result.isCancelled()) {
                network.cancel(false);
            }
        });

        return result;
    }

    private void closeAndRemove(Peer peer, IOException failure, PeerCloseReason reason) {
        try {
            peer.close(new PeerCloseException(
                    reason,
                    "BlockDownloadService",
                    "Closing peer after fatal block transport failure: " + failure,
                    failure
            ));
        } catch (IOException closeException) {
            failure.addSuppressed(closeException);
        } finally {
            peerManager.remove(peer);
        }
    }

    private static boolean isFatalTransportFailure(Peer peer, IOException failure) {
        if (!peer.isReady()) return true;
        Throwable current = failure;
        while (current != null) {
            if (current instanceof EOFException
                    || current instanceof SocketException
                    || current instanceof ClosedChannelException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static PeerCloseReason classifyTransportFailure(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof EOFException) return PeerCloseReason.REMOTE_EOF;
            current = current.getCause();
        }
        return PeerCloseReason.TRANSPORT_READ_FAILURE;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
            /* A request-scoped IOException is not automatically a dead TCP peer. */
            if (isFatalTransportFailure(peer, exception)) {
                closeAndRemove(peer, exception, classifyTransportFailure(exception));
            }
            throw exception;
        }
    }
}