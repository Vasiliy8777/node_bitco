package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.NotFoundMessage;

public final class NotFoundMessageCodec {

    private NotFoundMessageCodec() {
    }

    public static byte[] encode(
            NotFoundMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return InventoryVectorListCodec.encode(
                message.inventory()
        );
    }

    public static NotFoundMessage decode(
            byte[] payload
    ) {
        return new NotFoundMessage(
                InventoryVectorListCodec.decode(
                        payload
                )
        );
    }
}