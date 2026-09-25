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

public final class BlockSynchronizer {

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

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        PeerMessageDispatcher dispatcher =
                peer.messageDispatcher();

        CompletableFuture<Block> future =
                dispatcher.registerBlock(
                        blockHash
                );

        try {

            /*
             * An alternative transport (BIP152) may have completed this
             * scheduler-owned request immediately before registration. In that
             * case the dispatcher returns an already-completed future and no
             * duplicate full-block GETDATA is necessary.
             */
            if (future.isDone()) {
                return completedBlock(
                        future
                );
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

            peer.send(
                    BitcoinMessages.getData(
                            request
                    )
            );

            return completedBlock(
                    future
            );

        } finally {

            dispatcher.unregisterBlock(
                    blockHash,
                    future
            );
        }
    }

    private static Block completedBlock(
            CompletableFuture<Block> future
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
                throw runtimeException;
            }

            throw new IOException(
                    "Block download failed",
                    cause
            );
        }
    }
}