package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.bytes.ByteUtils;
import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.protocol.block.BlockHeader;

public final class BlockHeaderSerializer {

    private BlockHeaderSerializer() {
    }

    public static byte[] serialize(
            BlockHeader header
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        byte[] result = ByteUtils.concat(
                LittleEndian.int32(
                        header.version()
                ),

                header.previousBlockHash()
                        .bytes(),

                header.merkleRoot()
                        .bytes(),

                LittleEndian.uint32(
                        header.timestamp().value()
                ),

                LittleEndian.uint32(
                        header.bits().value()
                ),

                LittleEndian.uint32(
                        header.nonce().value()
                )
        );

        if (result.length != BlockHeader.SERIALIZED_SIZE) {
            throw new IllegalStateException(
                    "Block header must contain exactly 80 bytes"
            );
        }

        return result;
    }
}
