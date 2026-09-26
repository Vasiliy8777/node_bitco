package ru.bitcoin.node.p2p;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.BlockMessage;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.sync.BlockNotFoundException;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PeerMessageDispatcher {

    private final Peer peer;

    /*
     * Access to this map is synchronized on this dispatcher.
     *
     * Later the single reader thread will dispatch incoming
     * messages while request-producing threads register and
     * remove pending requests.
     */
    private final Map<Hash256, CompletableFuture<Block>>
            pendingBlocks =
            new HashMap<>();

    /*
     * A scheduler-owned alternative transport may finish after the scheduler
     * marks a block in-flight but just before BlockSynchronizer registers its
     * dispatcher future. Keep that narrowly-scoped early completion so the
     * subsequent registration consumes it without sending a duplicate request.
     */
    private final Map<Hash256, Block> precompletedBlocks =
            new HashMap<>();
    private CompletableFuture<HeadersMessage>
            pendingHeaders;

    /*
     * Once the peer has terminated, no newly registered request may remain
     * pending forever. Guarded by this dispatcher monitor together with the
     * pending request maps, so registration and terminal failure are atomic.
     */
    private IOException terminalFailure;

    public PeerMessageDispatcher(
            Peer peer
    ) {
        this.peer =
                Objects.requireNonNull(
                        peer,
                        "peer"
                );
    }

    public synchronized void unregisterBlock(
            Hash256 blockHash,
            CompletableFuture<Block> future
    ) {
        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        Objects.requireNonNull(
                future,
                "future"
        );

        pendingBlocks.remove(
                blockHash,
                future
        );
    }

    public synchronized CompletableFuture<HeadersMessage>
    registerHeaders() {

        CompletableFuture<HeadersMessage> future =
                new CompletableFuture<>();

        if (terminalFailure != null) {
            future.completeExceptionally(terminalFailure);
            return future;
        }

        if (pendingHeaders != null) {
            throw new IllegalStateException(
                    "Headers request is already pending"
            );
        }

        pendingHeaders =
                future;

        return future;
    }

    public synchronized void unregisterHeaders(
            CompletableFuture<HeadersMessage> future
    ) {
        Objects.requireNonNull(
                future,
                "future"
        );

        if (pendingHeaders == future) {
            pendingHeaders = null;
        }
    }

    /**
     * Completes an already registered block request from an alternative
     * transport path (for example BIP152 compact-block reconstruction).
     *
     * The request remains owned by the original downloader; this method only
     * supplies the authoritative block body to its pending future.
     */
    public boolean completePendingBlock(
            Block block
    ) {
        Objects.requireNonNull(
                block,
                "block"
        );

        CompletableFuture<Block> future;

        synchronized (this) {
            future = pendingBlocks.remove(
                    block.hash()
            );
        }

        if (future == null) {
            synchronized (this) {
                precompletedBlocks.putIfAbsent(
                        block.hash(),
                        block
                );
            }
            return true;
        }

        future.complete(
                block
        );

        return true;
    }

    public synchronized CompletableFuture<Block>
    registerBlock(
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        CompletableFuture<Block> future =
                new CompletableFuture<>();

        if (terminalFailure != null) {
            future.completeExceptionally(terminalFailure);
            return future;
        }

        if (pendingBlocks.containsKey(
                blockHash
        )) {
            throw new IllegalStateException(
                    "Block request is already pending: "
                            + blockHash.toDisplayHex()
            );
        }

        Block precompleted =
                precompletedBlocks.remove(
                        blockHash
                );

        if (precompleted != null) {
            future.complete(
                    precompleted
            );
            return future;
        }

        pendingBlocks.put(
                blockHash,
                future
        );

        return future;
    }

    public void dispatch(
            BitcoinMessage message
    ) throws IOException {

        Objects.requireNonNull(
                message,
                "message"
        );

        if ("block".equals(
                message.command()
        )) {
            dispatchBlock(
                    message
            );
            return;
        }

        if ("notfound".equals(
                message.command()
        )) {
            dispatchNotFound(
                    message
            );
            return;
        }

        if ("headers".equals(
                message.command()
        )) {
            dispatchHeaders(
                    message
            );
            return;
        }

        peer.handleMessage(
                message
        );
    }

    private void dispatchHeaders(
            BitcoinMessage message
    ) throws IOException {

        final HeadersMessage headers;

        try {
            headers =
                    BitcoinMessages.decodeHeaders(
                            message
                    );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Invalid headers message",
                    exception
            );
        }

        CompletableFuture<HeadersMessage> future;

        synchronized (this) {
            future =
                    pendingHeaders;

            if (future != null) {
                pendingHeaders = null;
            }
        }

        if (future == null) {
            peer.handleMessage(
                    message
            );

            return;
        }

        future.complete(
                headers
        );
    }

    public void failAllPending(
            IOException cause
    ) {
        Objects.requireNonNull(
                cause,
                "cause"
        );

        final Map<Hash256, CompletableFuture<Block>> blocks;
        final CompletableFuture<HeadersMessage> headersFuture;

        synchronized (this) {
            blocks =
                    new HashMap<>(
                            pendingBlocks
                    );

            pendingBlocks.clear();
            precompletedBlocks.clear();

            headersFuture =
                    pendingHeaders;

            pendingHeaders =
                    null;
        }

        for (CompletableFuture<Block> future :
                blocks.values()) {
            future.completeExceptionally(
                    cause
            );
        }

        if (headersFuture != null) {
            headersFuture.completeExceptionally(
                    cause
            );
        }
    }

    /**
     * Permanently terminates this dispatcher. Existing requests fail and any
     * later registration is completed exceptionally with the terminal cause.
     */
    public void close(
            IOException cause
    ) {
        Objects.requireNonNull(
                cause,
                "cause"
        );

        final Map<Hash256, CompletableFuture<Block>> blocks;
        final CompletableFuture<HeadersMessage> headersFuture;

        synchronized (this) {
            if (terminalFailure == null) {
                terminalFailure = cause;
            }

            blocks = new HashMap<>(pendingBlocks);
            pendingBlocks.clear();
            precompletedBlocks.clear();
            headersFuture = pendingHeaders;
            pendingHeaders = null;
        }

        for (CompletableFuture<Block> future : blocks.values()) {
            future.completeExceptionally(cause);
        }

        if (headersFuture != null) {
            headersFuture.completeExceptionally(cause);
        }
    }

    private void dispatchNotFound(
            BitcoinMessage message
    ) throws IOException {

        var notFound =
                BitcoinMessages.decodeNotFound(
                        message
                );

        boolean handled = false;

        for (var vector :
                notFound.inventory()) {

            if (!isBlockInventoryType(
                    vector.type()
            )) {
                continue;
            }

            CompletableFuture<Block> future;

            synchronized (this) {
                future =
                        pendingBlocks.remove(
                                vector.hash()
                        );
            }

            if (future == null) {
                continue;
            }

            handled = true;

            future.completeExceptionally(
                    new BlockNotFoundException(
                            vector.hash()
                    )
            );
        }

        /*
         * If notfound did not correspond to any pending
         * block request, preserve the existing generic
         * Peer handling path.
         */
        if (!handled) {
            peer.handleMessage(
                    message
            );
        }
    }

    private static boolean isBlockInventoryType(
            long type
    ) {
        return type
                == ru.bitcoin.node.p2p.message.InventoryVector.MSG_BLOCK
                || type
                == ru.bitcoin.node.p2p.message.InventoryVector.MSG_WITNESS_BLOCK;
    }

    public void failAllPendingBlocks(
            IOException cause
    ) {
        Objects.requireNonNull(
                cause,
                "cause"
        );

        final Map<Hash256, CompletableFuture<Block>> pending;

        synchronized (this) {
            pending =
                    new HashMap<>(
                            pendingBlocks
                    );

            pendingBlocks.clear();
            precompletedBlocks.clear();
        }

        for (CompletableFuture<Block> future :
                pending.values()) {

            future.completeExceptionally(
                    cause
            );
        }
    }

    private void dispatchBlock(
            BitcoinMessage message
    ) throws IOException {

        final BlockMessage blockMessage;

        try {
            blockMessage =
                    BitcoinMessages.decodeBlock(
                            message
                    );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Invalid block message",
                    exception
            );
        }

        Block block =
                blockMessage.block();

        CompletableFuture<Block> future;

        synchronized (this) {
            future =
                    pendingBlocks.remove(
                            block.hash()
                    );
        }

        if (future == null) {
            /*
             * There is currently no request waiting for this
             * block. Do not complete some unrelated request.
             *
             * For now the generic Peer layer is allowed to
             * see the message. Later unsolicited-block policy
             * can be made explicit at the dispatcher level.
             */
            peer.handleMessage(
                    message
            );

            return;
        }

        future.complete(
                block
        );
    }
}