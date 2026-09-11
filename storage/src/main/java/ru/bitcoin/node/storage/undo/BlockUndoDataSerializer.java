package ru.bitcoin.node.storage.undo;

import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.StoredUtxoSerializer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class BlockUndoDataSerializer {

    private static final int FORMAT_VERSION = 1;

    private static final int INT_SIZE = 4;

    private BlockUndoDataSerializer() {
    }

    public static byte[] serialize(
            BlockUndoData undoData
    ) {
        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                FORMAT_VERSION
        );

        List<TransactionUndo> transactions =
                undoData.transactions();

        writeIntLittleEndian(
                out,
                transactions.size()
        );

        for (TransactionUndo transactionUndo
                : transactions) {

            List<StoredUtxo> spentOutputs =
                    transactionUndo.spentOutputs();

            writeIntLittleEndian(
                    out,
                    spentOutputs.size()
            );

            for (StoredUtxo utxo
                    : spentOutputs) {

                byte[] serializedUtxo =
                        StoredUtxoSerializer.serialize(
                                utxo
                        );

                writeIntLittleEndian(
                        out,
                        serializedUtxo.length
                );

                out.writeBytes(
                        serializedUtxo
                );
            }
        }

        return out.toByteArray();
    }

    public static BlockUndoData deserialize(
            byte[] bytes
    ) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        /*
         * Минимум:
         *
         * 1 byte version
         * 4 bytes transaction count
         */
        if (bytes.length < 5) {
            throw new IllegalArgumentException(
                    "Block undo data is too short: "
                            + bytes.length
            );
        }

        Cursor cursor =
                new Cursor(bytes);

        int version =
                cursor.readUnsignedByte();

        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported BlockUndoData format version: "
                            + version
            );
        }

        int transactionCount =
                cursor.readNonNegativeInt();

        /*
         * Не создаём ArrayList(transactionCount).
         *
         * При повреждённой БД transactionCount может быть
         * огромным. Не позволяем данным из RocksDB
         * заставить JVM выделить гигантский массив.
         */
        List<TransactionUndo> transactions =
                new ArrayList<>();

        for (int transactionIndex = 0;
             transactionIndex < transactionCount;
             transactionIndex++) {

            int spentOutputCount =
                    cursor.readNonNegativeInt();

            List<StoredUtxo> spentOutputs =
                    new ArrayList<>();

            for (int outputIndex = 0;
                 outputIndex < spentOutputCount;
                 outputIndex++) {

                int utxoLength =
                        cursor.readNonNegativeInt();

                if (utxoLength == 0) {
                    throw new IllegalArgumentException(
                            "Stored UTXO length must be positive"
                    );
                }

                byte[] utxoBytes =
                        cursor.readBytes(
                                utxoLength
                        );

                StoredUtxo utxo =
                        StoredUtxoSerializer.deserialize(
                                utxoBytes
                        );

                spentOutputs.add(
                        utxo
                );
            }

            transactions.add(
                    new TransactionUndo(
                            spentOutputs
                    )
            );
        }

        /*
         * После чтения структуры никаких лишних байтов
         * оставаться не должно.
         */
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException(
                    "Unexpected trailing bytes in block undo data: "
                            + cursor.remaining()
            );
        }

        return new BlockUndoData(
                transactions
        );
    }

    private static void writeIntLittleEndian(
            ByteArrayOutputStream out,
            int value
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "value must not be negative"
            );
        }

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

    private static final class Cursor {

        private final byte[] bytes;

        private int offset;

        private Cursor(
                byte[] bytes
        ) {
            this.bytes = bytes;
        }

        private int remaining() {
            return bytes.length - offset;
        }

        private int readUnsignedByte() {

            requireRemaining(1);

            return bytes[offset++] & 0xFF;
        }

        private int readNonNegativeInt() {

            requireRemaining(INT_SIZE);

            int value =
                    (bytes[offset] & 0xFF)
                            | ((bytes[offset + 1] & 0xFF) << 8)
                            | ((bytes[offset + 2] & 0xFF) << 16)
                            | ((bytes[offset + 3] & 0xFF) << 24);

            offset += INT_SIZE;

            if (value < 0) {
                throw new IllegalArgumentException(
                        "Negative length/count in block undo data"
                );
            }

            return value;
        }

        private byte[] readBytes(
                int length
        ) {
            if (length < 0) {
                throw new IllegalArgumentException(
                        "length must not be negative"
                );
            }

            requireRemaining(
                    length
            );

            byte[] result =
                    new byte[length];

            System.arraycopy(
                    bytes,
                    offset,
                    result,
                    0,
                    length
            );

            offset += length;

            return result;
        }

        private void requireRemaining(
                int required
        ) {
            if (required < 0
                    || required > remaining()) {

                throw new IllegalArgumentException(
                        "Unexpected end of block undo data"
                );
            }
        }
    }
}