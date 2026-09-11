package ru.bitcoin.node.storage.utxo;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class StoredUtxoSerializer {

    private static final int FORMAT_VERSION = 1;

    private static final int FIXED_SIZE =
            1   // version
                    + 8 // amount
                    + 8 // height
                    + 1 // coinbase
                    + 4; // script length

    private StoredUtxoSerializer() {
    }

    public static byte[] serialize(
            StoredUtxo utxo
    ) {
        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        byte[] scriptPubKey =
                utxo.scriptPubKey();

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(
                        FIXED_SIZE + scriptPubKey.length
                );

        out.write(
                FORMAT_VERSION
        );

        writeLongLittleEndian(
                out,
                utxo.amount()
        );

        writeLongLittleEndian(
                out,
                utxo.height()
        );

        out.write(
                utxo.coinbase()
                        ? 1
                        : 0
        );

        writeIntLittleEndian(
                out,
                scriptPubKey.length
        );

        out.writeBytes(
                scriptPubKey
        );

        return out.toByteArray();
    }

    public static StoredUtxo deserialize(
            byte[] bytes
    ) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        if (bytes.length < FIXED_SIZE) {
            throw new IllegalArgumentException(
                    "Stored UTXO data is too short: "
                            + bytes.length
            );
        }

        int offset = 0;

        int version =
                bytes[offset++] & 0xFF;

        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported StoredUtxo format version: "
                            + version
            );
        }

        long amount =
                readLongLittleEndian(
                        bytes,
                        offset
                );

        offset += 8;

        long height =
                readLongLittleEndian(
                        bytes,
                        offset
                );

        offset += 8;

        int coinbaseValue =
                bytes[offset++] & 0xFF;

        if (coinbaseValue != 0
                && coinbaseValue != 1) {

            throw new IllegalArgumentException(
                    "Invalid coinbase flag: "
                            + coinbaseValue
            );
        }

        boolean coinbase =
                coinbaseValue == 1;

        int scriptLength =
                readIntLittleEndian(
                        bytes,
                        offset
                );

        offset += 4;

        if (scriptLength < 0) {
            throw new IllegalArgumentException(
                    "Negative scriptPubKey length"
            );
        }

        if (scriptLength
                != bytes.length - offset) {

            throw new IllegalArgumentException(
                    "Invalid scriptPubKey length: "
                            + scriptLength
                            + ", remaining bytes: "
                            + (bytes.length - offset)
            );
        }

        byte[] scriptPubKey =
                Arrays.copyOfRange(
                        bytes,
                        offset,
                        offset + scriptLength
                );

        return new StoredUtxo(
                amount,
                scriptPubKey,
                height,
                coinbase
        );
    }

    private static void writeIntLittleEndian(
            ByteArrayOutputStream out,
            int value
    ) {
        out.write(
                value & 0xFF
        );

        out.write(
                (value >>> 8) & 0xFF
        );

        out.write(
                (value >>> 16) & 0xFF
        );

        out.write(
                (value >>> 24) & 0xFF
        );
    }

    private static int readIntLittleEndian(
            byte[] bytes,
            int offset
    ) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeLongLittleEndian(
            ByteArrayOutputStream out,
            long value
    ) {
        for (int i = 0; i < 8; i++) {
            out.write(
                    (int) (
                            value >>> (8 * i)
                    ) & 0xFF
            );
        }
    }

    private static long readLongLittleEndian(
            byte[] bytes,
            int offset
    ) {
        long value = 0;

        for (int i = 0; i < 8; i++) {
            value |=
                    ((long) bytes[offset + i] & 0xFFL)
                            << (8 * i);
        }

        return value;
    }
}