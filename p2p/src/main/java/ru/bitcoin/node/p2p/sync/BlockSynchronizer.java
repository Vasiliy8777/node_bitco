package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

        while (true) {

            Optional<BitcoinMessage> optional =
                    peer.receive();

            if (optional.isEmpty()) {
                throw new IOException(
                        "Peer disconnected before sending block "
                                + blockHash.toDisplayHex()
                );
            }

            BitcoinMessage message =
                    optional.get();

            if ("block".equals(
                    message.command()
            )) {

                BlockMessage blockMessage =
                        BitcoinMessages.decodeBlock(
                                message
                        );

                Block block =
                        blockMessage.block();

                if (!blockHash.equals(
                        block.hash()
                )) {
                    throw new IOException(
                            "Received unexpected block: requested "
                                    + blockHash.toDisplayHex()
                                    + ", received "
                                    + block.hash()
                                    .toDisplayHex()
                    );
                }

                return block;
            }

            if ("notfound".equals(
                    message.command()
            )) {

                NotFoundMessage notFoundMessage =
                        BitcoinMessages.decodeNotFound(
                                message
                        );

                boolean requestedBlockNotFound =
                        notFoundMessage.inventory()
                                .stream()
                                .anyMatch(
                                        vector ->
                                                blockHash.equals(
                                                        vector.hash()
                                                )
                                                        && isBlockInventoryType(
                                                        vector.type()
                                                )
                                );

                if (requestedBlockNotFound) {
                    throw new BlockNotFoundException(
                            blockHash
                    );
                }

                /*
                 * This notfound does not refer to the block
                 * currently being downloaded. Treat it as an
                 * unrelated peer message.
                 */
                peer.handleMessage(
                        message
                );

                continue;
            }

            peer.handleMessage(
                    message
            );
        }
    }

    private static boolean isBlockInventoryType(
            long type
    ) {
        return type == InventoryVector.MSG_BLOCK
                || type == InventoryVector.MSG_WITNESS_BLOCK;
    }
}