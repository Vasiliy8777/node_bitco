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
        return serialize(
                block,
                true
        );
    }

    public static byte[] serializeLegacy(
            Block block
    ) {
        return serialize(
                block,
                false
        );
    }

    private static byte[] serialize(
            Block block,
            boolean includeWitness
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

            byte[] serializedTransaction =
                    includeWitness
                            ? TransactionSerializer.serialize(
                            transaction
                    )
                            : TransactionSerializer.serializeLegacy(
                            transaction
                    );

            out.writeBytes(
                    serializedTransaction
            );
        }

        return out.toByteArray();
    }
}