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
            new AtomicInteger(32);

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
                        future,
                        peer,
                        blockHash
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

            try {
                peer.send(
                        BitcoinMessages.getData(
                                request
                        )
                );
            } catch (IllegalStateException exception) {
                /*
                 * The background reader can transition the peer out of READY
                 * after registerBlock() but immediately before send(). Peer.send()
                 * deliberately reports that state race as IllegalStateException.
                 * For a block-download worker this is a recoverable transport race,
                 * not a scheduler/programming failure: BlockDownloadService must see
                 * IOException so it can remove the dead peer and leave the request
                 * pending for a replacement peer.
                 *
                 * Preserve genuine API/state bugs: only translate the exception when
                 * the peer really ceased to be READY.
                 */
                if (peer.isReady()) {
                    throw exception;
                }

                throw new IOException(
                        "Peer disconnected before block GETDATA could be sent for "
                                + blockHash.toDisplayHex(),
                        exception
                );
            }

            boolean diagnostic = DIAGNOSTIC_LOG_BUDGET.getAndDecrement() > 0;
            if (diagnostic) {
                log.log(
                        System.Logger.Level.INFO,
                        "IBD GETDATA sent: hash={0}, peer={1}",
                        blockHash.toDisplayHex(),
                        peer.remoteAddress()
                );
            }

            Block block = completedBlock(
                    future,
                    peer,
                    blockHash
            );

            if (diagnostic) {
                log.log(
                        System.Logger.Level.INFO,
                        "IBD BLOCK received: hash={0}, peer={1}",
                        blockHash.toDisplayHex(),
                        peer.remoteAddress()
                );
            }

            return block;

        } finally {

            dispatcher.unregisterBlock(
                    blockHash,
                    future
            );
        }
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
}