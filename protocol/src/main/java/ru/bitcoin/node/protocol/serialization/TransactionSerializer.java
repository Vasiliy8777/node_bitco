package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;


import java.io.ByteArrayOutputStream;

public final class TransactionSerializer {

    private static final int SEGWIT_MARKER = 0x00;
    private static final int SEGWIT_FLAG = 0x01;

    private TransactionSerializer() {
    }

    public static byte[] serialize(
            Transaction transaction
    ) {
        return serialize(
                transaction,
                transaction.hasWitness()
        );
    }

    public static byte[] serializeLegacy(
            Transaction transaction
    ) {
        return serialize(
                transaction,
                false
        );
    }

    private static byte[] serialize(
            Transaction transaction,
            boolean includeWitness
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        write(
                out,
                LittleEndian.int32(
                        transaction.version()
                )
        );

        boolean useWitness =
                includeWitness
                        && transaction.hasWitness();

        if (useWitness) {
            out.write(SEGWIT_MARKER);
            out.write(SEGWIT_FLAG);
        }

        write(
                out,
                CompactSize.encode(
                        transaction.inputs().size()
                )
        );

        for (TxIn input : transaction.inputs()) {
            writeInput(out, input);
        }

        write(
                out,
                CompactSize.encode(
                        transaction.outputs().size()
                )
        );

        for (TxOut output : transaction.outputs()) {
            writeOutput(out, output);
        }

        if (useWitness) {
            for (TxIn input : transaction.inputs()) {
                writeWitness(
                        out,
                        input.witness()
                );
            }
        }

        write(
                out,
                LittleEndian.uint32(
                        transaction.lockTime().value()
                )
        );

        return out.toByteArray();
    }

    private static void writeInput(
            ByteArrayOutputStream out,
            TxIn input
    ) {
        /*
         * Hash256 хранит raw digest bytes.
         *
         * Именно в таком порядке hash предыдущей
         * транзакции находится внутри wire serialization.
         */
        write(
                out,
                input.previousOutput()
                        .transactionId()
                        .bytes()
        );

        write(
                out,
                LittleEndian.uint32(
                        input.previousOutput()
                                .outputIndex()
                                .value()
                )
        );

        byte[] script =
                input.scriptSig();

        write(
                out,
                CompactSize.encode(script.length)
        );

        write(out, script);

        write(
                out,
                LittleEndian.uint32(
                        input.sequence().value()
                )
        );
    }

    private static void writeOutput(
            ByteArrayOutputStream out,
            TxOut output
    ) {
        write(
                out,
                LittleEndian.int64(
                        output.value()
                )
        );

        byte[] script =
                output.scriptPubKey();

        write(
                out,
                CompactSize.encode(script.length)
        );

        write(out, script);
    }

    private static void writeWitness(
            ByteArrayOutputStream out,
            Witness witness
    ) {
        write(
                out,
                CompactSize.encode(
                        witness.size()
                )
        );

        for (byte[] item : witness.items()) {

            write(
                    out,
                    CompactSize.encode(
                            item.length
                    )
            );

            write(out, item);
        }
    }

    private static void write(
            ByteArrayOutputStream out,
            byte[] bytes
    ) {
        out.writeBytes(bytes);
    }
}