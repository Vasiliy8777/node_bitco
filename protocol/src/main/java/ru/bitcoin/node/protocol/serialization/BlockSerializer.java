package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.ByteArrayOutputStream;

public final class BlockSerializer {

    private BlockSerializer() {
    }

    public static byte[] serialize(
            Block block
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.writeBytes(
                BlockHeaderSerializer.serialize(
                        block.header()
                )
        );

        out.writeBytes(
                CompactSize.encode(
                        block.transactions().size()
                )
        );

        for (Transaction transaction
                : block.transactions()) {

            out.writeBytes(
                    TransactionSerializer.serialize(
                            transaction
                    )
            );
        }

        return out.toByteArray();
    }
}