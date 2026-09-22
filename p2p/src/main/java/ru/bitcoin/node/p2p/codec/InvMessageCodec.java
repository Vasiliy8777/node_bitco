package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.InvMessage;

public final class InvMessageCodec {

    private InvMessageCodec() {
    }

    public static byte[] encode(
            InvMessage message
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

    public static InvMessage decode(
            byte[] payload
    ) {
        return new InvMessage(
                InventoryVectorListCodec.decode(
                        payload
                )
        );
    }
}