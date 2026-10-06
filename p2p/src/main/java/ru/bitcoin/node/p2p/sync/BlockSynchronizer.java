package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerMessageDispatcher;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.GetDataMessage;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlockSynchronizer {

    private static final System.Logger log =
            System.getLogger(BlockSynchronizer.class.getName());
    private static final AtomicInteger DIAGNOSTIC_LOG_BUDGET =
            new AtomicInteger(0);

    private final Peer peer;

    public BlockSynchronizer(
            Peer peer
    ) {
        this.peer =
                Objects.requireNonNull(
                        peer,
                        "peer"
                );
    }

    public Block download(
            Hash256 blockHash
    ) throws IOException {

        CompletableFuture<Block> future =
                downloadAsync(blockHash);

        return completedBlock(
                future,
                peer,
                blockHash
        );
    }

    /**
     * Starts a full-block request without occupying a platform thread while the
     * peer reader waits for the BLOCK/NOTFOUND response. The returned future is
     * completed directly by PeerMessageDispatcher. Dispatcher ownership is
     * released on every terminal path, including cancellation.
     */
    public CompletableFuture<Block> downloadAsync(
            Hash256 blockHash
    ) {
        return downloadAsync(blockHash, false);
    }

    public CompletableFuture<Block> downloadAsync(Hash256 blockHash, boolean coreBlockRequest) {

        Objects.requireNonNull(blockHash, "blockHash");

        PeerMessageDispatcher dispatcher =
                peer.messageDispatcher();

        CompletableFuture<Block> source =
                coreBlockRequest ? dispatcher.registerCoreBlock(blockHash) : dispatcher.registerBlock(blockHash);

        CompletableFuture<Block> result =
                new CompletableFuture<>();

        source.whenComplete((block, failure) -> {
            if (failure == null) {
                result.complete(block);
                return;
            }

            Throwable cause = unwrapCompletionFailure(failure);

            if (cause instanceof IOException) {
                result.completeExceptionally(cause);
                return;
            }

            if (cause instanceof RuntimeException runtimeException) {
                if (peer.isReady()) {
                    result.completeExceptionally(runtimeException);
                } else {
                    result.completeExceptionally(
                            new IOException(
                                    "Peer disconnected while waiting for block "
                                            + blockHash.toDisplayHex(),
                                    runtimeException
                            )
                    );
                }
                return;
            }

            result.completeExceptionally(
                    new IOException("Block download failed", cause)
            );
        });

        result.whenComplete((ignoredBlock, ignoredFailure) ->
                dispatcher.unregisterBlock(blockHash, source));

        if (source.isDone()) {
            return result;
        }

        GetDataMessage request =
                new GetDataMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        blockHash
                                )
                        )
                );

        try {
            peer.send(BitcoinMessages.getData(request));
        } catch (IOException exception) {
            result.completeExceptionally(exception);
        } catch (IllegalStateException exception) {
            if (peer.isReady()) {
                result.completeExceptionally(exception);
            } else {
                result.completeExceptionally(
                        new IOException(
                                "Peer disconnected before block GETDATA could be sent for "
                                        + blockHash.toDisplayHex(),
                                exception
                        )
                );
            }
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }

        return result;
    }


    /**
     * Registers a set of block waits first and then sends one GETDATA carrying
     * all hashes. This is the wire-efficient path used by replicated IBD.
     */
    public java.util.Map<Hash256, CompletableFuture<Block>> downloadBatchAsync(
            List<Hash256> blockHashes
    ) {
        Objects.requireNonNull(blockHashes, "blockHashes");
        if (blockHashes.isEmpty()) return java.util.Map.of();

        PeerMessageDispatcher dispatcher = peer.messageDispatcher();
        java.util.LinkedHashMap<Hash256, CompletableFuture<Block>> results = new java.util.LinkedHashMap<>();
        java.util.ArrayList<InventoryVector> inventory = new java.util.ArrayList<>();

        try {
            for (Hash256 hash : blockHashes) {
                Objects.requireNonNull(hash, "blockHash");
                CompletableFuture<Block> source = dispatcher.registerBlock(hash);
                CompletableFuture<Block> result = new CompletableFuture<>();
                results.put(hash, result);

                source.whenComplete((block, failure) -> {
                    if (failure == null) result.complete(block);
                    else {
                        Throwable cause = unwrapCompletionFailure(failure);
                        if (cause instanceof IOException) result.completeExceptionally(cause);
                        else if (cause instanceof RuntimeException runtimeException) result.completeExceptionally(runtimeException);
                        else result.completeExceptionally(new IOException("Block download failed", cause));
                    }
                });
                result.whenComplete((ignoredBlock, ignoredFailure) -> dispatcher.unregisterBlock(hash, source));
                if (!source.isDone()) {
                    inventory.add(new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, hash));
                }
            }

            if (!inventory.isEmpty()) {
                peer.send(BitcoinMessages.getData(new GetDataMessage(inventory)));
            }
        } catch (Throwable failure) {
            for (CompletableFuture<Block> result : results.values()) {
                if (!result.isDone()) result.completeExceptionally(failure);
            }
            // Keep the returned map total: callers reserve every requested hash
            // before network I/O and must be able to release every reservation.
            for (Hash256 hash : blockHashes) {
                results.computeIfAbsent(hash, ignored -> CompletableFuture.failedFuture(failure));
            }
        }
        return java.util.Collections.unmodifiableMap(results);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Block completedBlock(
            CompletableFuture<Block> future,
            Peer peer,
            Hash256 blockHash
    ) throws IOException {

        try {
            return future.join();

        } catch (CompletionException exception) {

            Throwable cause =
                    exception.getCause();

            if (cause instanceof IOException ioException) {
                throw ioException;
            }

            if (cause instanceof RuntimeException runtimeException) {
                /*
                 * The peer reader may complete a pending dispatcher future with a
                 * runtime state exception when the transport disappears after GETDATA
                 * was sent. From the scheduler's point of view this is the same
                 * recoverable peer-loss race as a send-side disconnect: the logical
                 * block request must remain pending and be retried on a replacement
                 * peer. Do not hide genuine runtime bugs while the peer is still READY.
                 */
                if (peer.isReady()) {
                    throw runtimeException;
                }

                throw new IOException(
                        "Peer disconnected while waiting for block "
                                + blockHash.toDisplayHex(),
                        runtimeException
                );
            }

            throw new IOException(
                    "Block download failed",
                    cause
            );
        }
    }
    /** Diagnostic logging must never turn a normal disconnect into a worker failure. */
    private static String diagnosticPeerAddress(Peer peer) {
        try {
            return String.valueOf(peer.remoteAddress());
        } catch (RuntimeException exception) {
            return "<disconnected>";
        }
    }

}
