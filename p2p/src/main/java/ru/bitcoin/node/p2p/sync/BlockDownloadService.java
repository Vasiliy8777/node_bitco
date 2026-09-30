package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
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
                closeAndRemove(peer, ioException);
                result.completeExceptionally(ioException);
                return;
            }

            if (cause instanceof RuntimeException runtimeException) {
                result.completeExceptionally(runtimeException);
                return;
            }

            IOException ioException =
                    new IOException("Block download failed", cause);
            closeAndRemove(peer, ioException);
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

    private void closeAndRemove(Peer peer, IOException failure) {
        try {
            peer.close();
        } catch (IOException closeException) {
            failure.addSuppressed(closeException);
        } finally {
            peerManager.remove(peer);
        }
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