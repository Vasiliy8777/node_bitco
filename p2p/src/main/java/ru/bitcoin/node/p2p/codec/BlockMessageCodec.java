package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.BlockMessage;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;

public final class BlockMessageCodec {

    private BlockMessageCodec() {
    }

    public static byte[] encode(
            BlockMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return BlockSerializer.serialize(
                message.block()
        );
    }

    public static BlockMessage decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        return new BlockMessage(
                BlockParser.parse(
                        payload
                )
        );
    }
}