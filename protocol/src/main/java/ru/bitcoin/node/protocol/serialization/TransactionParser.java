package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.ArrayList;
import java.util.List;

public final class TransactionParser {

    private TransactionParser() {
    }

    public static Transaction parse(
            byte[] bytes
    ) {
        return parse(bytes, true);
    }

    public static Transaction parse(byte[] bytes, boolean allowWitness) {
        BitcoinReader reader =
                new BitcoinReader(bytes);

        Transaction transaction =
                parse(reader, allowWitness);

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected bytes after transaction"
            );
        }

        return transaction;
    }

    public static Transaction parse(
            BitcoinReader reader
    ) {
        return parse(reader, true);
    }

    public static Transaction parse(BitcoinReader reader, boolean allowWitness) {
        int version =
                reader.readInt32LE();

        long inputCount =
                reader.readCompactSize();

        boolean hasWitness = false;
        boolean emptyOutputsRead = false;

        /*
         * SegWit marker:
         *
         * vin count = 0
         *
         * Следующий байт является flag.
         */
        if (inputCount == 0 && allowWitness) {

            int flag =
                    reader.readUnsignedByte();

            if (flag == 0) {
                emptyOutputsRead = true;
            }

            /*
             * Пока поддерживаем стандартный witness flag 0x01.
             */
            if (flag != 0 && flag != 0x01) {
                throw new IllegalArgumentException(
                        "Unsupported transaction witness flag: "
                                + flag
                );
            }

            hasWitness = flag != 0;
            if (hasWitness) inputCount = reader.readCompactSize();
        }

        int inputsSize =
                reader.checkedCollectionSize(
                        inputCount,
                        41,
                        "input count"
                );

        List<TxIn> inputs =
                new ArrayList<>(inputsSize);

        /*
         * Witness физически будет прочитан позже.
         *
         * Поэтому пока создаём inputs без witness.
         */
        for (int i = 0; i < inputsSize; i++) {
            inputs.add(
                    readInput(reader)
            );
        }

        long outputCount =
                emptyOutputsRead ? 0 : reader.readCompactSize();

        int outputsSize =
                reader.checkedCollectionSize(
                        outputCount,
                        9,
                        "output count"
                );

        List<TxOut> outputs =
                new ArrayList<>(outputsSize);

        for (int i = 0; i < outputsSize; i++) {
            outputs.add(
                    readOutput(reader)
            );
        }

        if (hasWitness) {

            List<TxIn> withWitness =
                    new ArrayList<>(inputsSize);

            for (TxIn input : inputs) {

                Witness witness =
                        readWitness(reader);

                withWitness.add(
                        new TxIn(
                                input.previousOutput(),
                                input.scriptSig(),
                                input.sequence(),
                                witness
                        )
                );
            }

            inputs = withWitness;
            if (inputs.stream().allMatch(input -> input.witness().isEmpty())) {
                throw new IllegalArgumentException("Superfluous witness record");
            }
        }

        UInt32 lockTime =
                new UInt32(
                        reader.readUInt32LE()
                );

        return new Transaction(
                version,
                inputs,
                outputs,
                lockTime
        );
    }

    private static TxIn readInput(
            BitcoinReader reader
    ) {
        Hash256 previousTxId =
                new Hash256(
                        reader.readBytes(
                                Hash256.LENGTH
                        )
                );

        UInt32 outputIndex =
                new UInt32(
                        reader.readUInt32LE()
                );

        byte[] scriptSig =
                reader.readCompactBytes();

        UInt32 sequence =
                new UInt32(
                        reader.readUInt32LE()
                );

        return new TxIn(
                new OutPoint(
                        previousTxId,
                        outputIndex
                ),
                scriptSig,
                sequence
        );
    }

    private static TxOut readOutput(
            BitcoinReader reader
    ) {
        long value =
                reader.readInt64LE();

        byte[] scriptPubKey =
                reader.readCompactBytes();

        return new TxOut(
                value,
                scriptPubKey
        );
    }

    private static Witness readWitness(
            BitcoinReader reader
    ) {
        long count =
                reader.readCompactSize();

        int size =
                reader.checkedCollectionSize(
                        count,
                        1,
                        "witness item count"
                );

        List<byte[]> items =
                new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            items.add(
                    reader.readCompactBytes()
            );
        }

        return new Witness(items);
    }

}
