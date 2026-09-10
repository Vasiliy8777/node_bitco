package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

public final class BlockHeaderParser {

    private BlockHeaderParser() {
    }

    public static BlockHeader parse(
            byte[] bytes
    ) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        if (bytes.length != BlockHeader.SERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                    "Block header must contain exactly 80 bytes"
            );
        }

        BitcoinReader reader =
                new BitcoinReader(bytes);

        return parse(reader);
    }

    public static BlockHeader parse(
            BitcoinReader reader
    ) {
        if (reader == null) {
            throw new IllegalArgumentException(
                    "reader must not be null"
            );
        }

        int version =
                reader.readInt32LE();

        Hash256 previousBlockHash =
                new Hash256(
                        reader.readBytes(
                                Hash256.LENGTH
                        )
                );

        Hash256 merkleRoot =
                new Hash256(
                        reader.readBytes(
                                Hash256.LENGTH
                        )
                );

        UInt32 timestamp =
                new UInt32(
                        reader.readUInt32LE()
                );

        UInt32 bits =
                new UInt32(
                        reader.readUInt32LE()
                );

        UInt32 nonce =
                new UInt32(
                        reader.readUInt32LE()
                );

        return new BlockHeader(
                version,
                previousBlockHash,
                merkleRoot,
                timestamp,
                bits,
                nonce
        );
    }
}
