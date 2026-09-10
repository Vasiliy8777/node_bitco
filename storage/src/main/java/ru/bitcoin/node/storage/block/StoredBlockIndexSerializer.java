package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.bytes.ByteUtils;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class StoredBlockIndexSerializer {

    private static final byte FORMAT_VERSION = 1;

    private static final int HASH_SIZE = 32;
    private static final int HEADER_SIZE = 80;
    private static final int CHAIN_WORK_SIZE = 32;

    public static final int SERIALIZED_SIZE =
            1
                    + HASH_SIZE
                    + HEADER_SIZE
                    + Long.BYTES
                    + HASH_SIZE
                    + CHAIN_WORK_SIZE;

    private StoredBlockIndexSerializer() {
    }

    public static byte[] serialize(
            StoredBlockIndex index
    ) {
        if (index == null) {
            throw new IllegalArgumentException(
                    "index must not be null"
            );
        }

        ByteBuffer buffer =
                ByteBuffer.allocate(SERIALIZED_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(FORMAT_VERSION);

        putHash(
                buffer,
                index.hash()
        );

        putHeader(
                buffer,
                index.header()
        );

        buffer.putLong(
                index.height()
        );

        putHash(
                buffer,
                index.previousBlockHash()
        );

        buffer.put(
                toFixedUnsignedBytes(
                        index.chainWork(),
                        CHAIN_WORK_SIZE
                )
        );

        return buffer.array();
    }

    public static StoredBlockIndex deserialize(
            byte[] bytes
    ) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        if (bytes.length != SERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid StoredBlockIndex size: "
                            + bytes.length
                            + ", expected "
                            + SERIALIZED_SIZE
            );
        }

        ByteBuffer buffer =
                ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN);

        byte version =
                buffer.get();

        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported StoredBlockIndex format version: "
                            + Byte.toUnsignedInt(version)
            );
        }

        Hash256 hash =
                readHash(buffer);

        BlockHeader header =
                readHeader(buffer);

        long height =
                buffer.getLong();

        if (height < 0) {
            throw new IllegalArgumentException(
                    "Stored height must not be negative"
            );
        }

        Hash256 previousBlockHash =
                readHash(buffer);

        byte[] chainWorkBytes =
                new byte[CHAIN_WORK_SIZE];

        buffer.get(chainWorkBytes);

        BigInteger chainWork =
                new BigInteger(
                        1,
                        chainWorkBytes
                );

        /*
         * Защита от повреждённой/несогласованной записи.
         */
        if (!header.hash().equals(hash)) {
            throw new IllegalArgumentException(
                    "Stored block hash does not match header hash"
            );
        }

        if (!header.previousBlockHash().equals(
                previousBlockHash
        )) {
            throw new IllegalArgumentException(
                    "Stored previous block hash does not match header"
            );
        }

        return new StoredBlockIndex(
                hash,
                header,
                height,
                previousBlockHash,
                chainWork
        );
    }

    private static void putHeader(
            ByteBuffer buffer,
            BlockHeader header
    ) {
        buffer.putInt(
                header.version()
        );

        putHash(
                buffer,
                header.previousBlockHash()
        );

        putHash(
                buffer,
                header.merkleRoot()
        );

        buffer.putInt(
                header.timestamp().intBits()
        );

        buffer.putInt(
                header.bits().intBits()
        );

        buffer.putInt(
                header.nonce().intBits()
        );
    }

    private static BlockHeader readHeader(
            ByteBuffer buffer
    ) {
        int version =
                buffer.getInt();

        Hash256 previousBlockHash =
                readHash(buffer);

        Hash256 merkleRoot =
                readHash(buffer);

        long timestamp =
                Integer.toUnsignedLong(
                        buffer.getInt()
                );

        long bits =
                Integer.toUnsignedLong(
                        buffer.getInt()
                );

        long nonce =
                Integer.toUnsignedLong(
                        buffer.getInt()
                );

        return new BlockHeader(
                version,
                previousBlockHash,
                merkleRoot,
                new UInt32(timestamp),
                new UInt32(bits),
                new UInt32(nonce)
        );
    }

    private static void putHash(
            ByteBuffer buffer,
            Hash256 hash
    ) {
        buffer.put(
                hash.bytes()
        );
    }

    private static Hash256 readHash(
            ByteBuffer buffer
    ) {
        byte[] raw =
                new byte[HASH_SIZE];

        buffer.get(raw);

        /*
         * Hash256 хранит raw digest bytes.
         *
         * fromDisplayHex ожидает привычный Bitcoin
         * display-order, поэтому перед преобразованием
         * разворачиваем raw bytes.
         */
        return Hash256.fromDisplayHex(
                HexUtils.encode(
                        ByteUtils.reverse(raw)
                )
        );
    }

    private static byte[] toFixedUnsignedBytes(
            BigInteger value,
            int size
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "value must not be negative"
            );
        }

        byte[] raw =
                value.toByteArray();

        /*
         * BigInteger может добавить ведущий 00,
         * чтобы сохранить положительный знак.
         */
        if (raw.length == size + 1
                && raw[0] == 0) {

            raw = Arrays.copyOfRange(
                    raw,
                    1,
                    raw.length
            );
        }

        if (raw.length > size) {
            throw new IllegalArgumentException(
                    "value does not fit into "
                            + size
                            + " bytes"
            );
        }

        byte[] result =
                new byte[size];

        System.arraycopy(
                raw,
                0,
                result,
                size - raw.length,
                raw.length
        );

        return result;
    }
}