package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.BlockMessage;
import ru.bitcoin.node.p2p.message.GetDataMessage;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BlockSynchronizer {

    private final PeerConnection connection;
    private final Peer peer;

    public BlockSynchronizer(
            PeerConnection connection,
            Peer peer
    ) {
        this.connection =
                Objects.requireNonNull(
                        connection,
                        "connection"
                );

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

        connection.send(
                BitcoinMessages.getData(
                        request
                )
        );

        while (true) {

            Optional<BitcoinMessage> optional =
                    connection.receive();

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

            peer.handleMessage(
                    message
            );
        }
    }
}