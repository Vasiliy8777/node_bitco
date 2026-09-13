package ru.bitcoin.node.script;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class LegacySignatureHash {

    /*
     * Historical SIGHASH_SINGLE bug result.
     *
     * Bitcoin Core returns uint256::ONE here.
     * In the raw 32-byte representation used by our
     * crypto layer this is:
     *
     * 01 00 00 ... 00
     */
    private static final byte[] HASH_ONE =
            createHashOne();

    private LegacySignatureHash() {
    }

    public static byte[] calculate(
            Transaction transaction,
            int inputIndex,
            byte[] scriptCode,
            int hashType
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (scriptCode == null) {
            throw new IllegalArgumentException(
                    "scriptCode must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        int baseType =
                SignatureHashType.baseType(
                        hashType
                );

        /*
         * Historical consensus bug.
         *
         * SIGHASH_SINGLE with no corresponding output
         * returns uint256(1) instead of hashing a
         * serialized transaction.
         */
        if (baseType == SignatureHashType.SIGHASH_SINGLE
                && inputIndex >= transaction.outputs().size()) {

            return HASH_ONE.clone();
        }

        byte[] normalizedScriptCode =
                removeCodeSeparators(
                        scriptCode
                );

        /*List<TxIn> inputs =
                buildInputs(
                        transaction,
                        inputIndex,
                        scriptCode,
                        hashType,
                        baseType
                );*/
        List<TxIn> inputs =
                buildInputs(
                        transaction,
                        inputIndex,
                        normalizedScriptCode,
                        hashType,
                        baseType
                );

        List<TxOut> outputs =
                buildOutputs(
                        transaction,
                        inputIndex,
                        baseType
                );

        Transaction signingTransaction =
                new Transaction(
                        transaction.version(),
                        inputs,
                        outputs,
                        transaction.lockTime()
                );

        byte[] serialized =
                TransactionSerializer.serializeLegacy(
                        signingTransaction
                );

        ByteArrayOutputStream preimage =
                new ByteArrayOutputStream(
                        serialized.length + 4
                );

        preimage.writeBytes(serialized);

        /*
         * nHashType is serialized as a 4-byte
         * little-endian integer.
         */
        preimage.writeBytes(
                LittleEndian.uint32(
                        Integer.toUnsignedLong(
                                hashType
                        )
                )
        );

        return Hash256Digest.hashBytes(
                preimage.toByteArray()
        );
    }

    private static List<TxIn> buildInputs(
            Transaction transaction,
            int inputIndex,
            byte[] scriptCode,
            int hashType,
            int baseType
    ) {
        if (SignatureHashType.isAnyoneCanPay(
                hashType
        )) {

            TxIn current =
                    transaction.inputs()
                            .get(inputIndex);

            return List.of(
                    new TxIn(
                            current.previousOutput(),
                            scriptCode,
                            current.sequence()
                    )
            );
        }

        List<TxIn> result =
                new ArrayList<>(
                        transaction.inputs().size()
                );

        for (int i = 0;
             i < transaction.inputs().size();
             i++) {

            TxIn original =
                    transaction.inputs()
                            .get(i);

            byte[] inputScript =
                    i == inputIndex
                            ? scriptCode
                            : new byte[0];

            UInt32 sequence =
                    original.sequence();

            /*
             * For NONE/SINGLE every input other than
             * the one being signed gets sequence = 0.
             */
            if (i != inputIndex
                    && (
                    baseType
                            == SignatureHashType.SIGHASH_NONE
                            || baseType
                            == SignatureHashType.SIGHASH_SINGLE
            )) {

                sequence =
                        new UInt32(0);
            }

            result.add(
                    new TxIn(
                            original.previousOutput(),
                            inputScript,
                            sequence
                    )
            );
        }

        return List.copyOf(result);
    }

    private static List<TxOut> buildOutputs(
            Transaction transaction,
            int inputIndex,
            int baseType
    ) {
        if (baseType
                == SignatureHashType.SIGHASH_NONE) {

            return List.of();
        }

        if (baseType
                != SignatureHashType.SIGHASH_SINGLE) {

            return transaction.outputs();
        }

        List<TxOut> result =
                new ArrayList<>(
                        inputIndex + 1
                );

        /*
         * Historical SIGHASH_SINGLE behavior:
         *
         * outputs before inputIndex become "null"
         * outputs:
         *
         * value = -1
         * scriptPubKey = empty
         */
        for (int i = 0;
             i < inputIndex;
             i++) {

            result.add(
                    new TxOut(
                            -1L,
                            new byte[0]
                    )
            );
        }

        result.add(
                transaction.outputs()
                        .get(inputIndex)
        );

        return List.copyOf(result);
    }

    private static byte[] createHashOne() {

        byte[] result =
                new byte[32];

        result[0] =
                0x01;

        return result;
    }
    private static byte[] removeCodeSeparators(
            byte[] scriptCode
    ) {
        if (scriptCode.length == 0) {
            return scriptCode;
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(
                        scriptCode.length
                );

        int position = 0;

        while (position < scriptCode.length) {

            int opcode =
                    Byte.toUnsignedInt(
                            scriptCode[position]
                    );

            position++;

            /*
             * Удаляем только реальные OP_CODESEPARATOR.
             *
             * Байты 0xab внутри pushed-data удалять нельзя.
             */
            if (opcode == Opcode.OP_CODESEPARATOR) {
                continue;
            }

            out.write(opcode);

            if (opcode >= 0x01
                    && opcode <= 0x4b) {

                int length = opcode;

                requireScriptBytes(
                        scriptCode,
                        position,
                        length
                );

                out.writeBytes(
                        java.util.Arrays.copyOfRange(
                                scriptCode,
                                position,
                                position + length
                        )
                );

                position += length;

            } else if (opcode == Opcode.OP_PUSHDATA1) {

                requireScriptBytes(
                        scriptCode,
                        position,
                        1
                );

                int length =
                        Byte.toUnsignedInt(
                                scriptCode[position]
                        );

                out.write(
                        scriptCode[position]
                );

                position++;

                requireScriptBytes(
                        scriptCode,
                        position,
                        length
                );

                out.writeBytes(
                        java.util.Arrays.copyOfRange(
                                scriptCode,
                                position,
                                position + length
                        )
                );

                position += length;

            } else if (opcode == Opcode.OP_PUSHDATA2) {

                requireScriptBytes(
                        scriptCode,
                        position,
                        2
                );

                int length =
                        Byte.toUnsignedInt(
                                scriptCode[position]
                        )
                                |
                                (
                                        Byte.toUnsignedInt(
                                                scriptCode[
                                                        position + 1
                                                        ]
                                        )
                                                << 8
                                );

                out.write(
                        scriptCode[position]
                );

                out.write(
                        scriptCode[position + 1]
                );

                position += 2;

                requireScriptBytes(
                        scriptCode,
                        position,
                        length
                );

                out.writeBytes(
                        java.util.Arrays.copyOfRange(
                                scriptCode,
                                position,
                                position + length
                        )
                );

                position += length;

            } else if (opcode == Opcode.OP_PUSHDATA4) {

                requireScriptBytes(
                        scriptCode,
                        position,
                        4
                );

                long length =
                        Integer.toUnsignedLong(
                                Byte.toUnsignedInt(
                                        scriptCode[position]
                                )
                                        |
                                        (
                                                Byte.toUnsignedInt(
                                                        scriptCode[
                                                                position + 1
                                                                ]
                                                )
                                                        << 8
                                        )
                                        |
                                        (
                                                Byte.toUnsignedInt(
                                                        scriptCode[
                                                                position + 2
                                                                ]
                                                )
                                                        << 16
                                        )
                                        |
                                        (
                                                Byte.toUnsignedInt(
                                                        scriptCode[
                                                                position + 3
                                                                ]
                                                )
                                                        << 24
                                        )
                        );

                out.write(
                        scriptCode[position]
                );

                out.write(
                        scriptCode[position + 1]
                );

                out.write(
                        scriptCode[position + 2]
                );

                out.write(
                        scriptCode[position + 3]
                );

                position += 4;

                if (length > Integer.MAX_VALUE) {
                    throw new ScriptParseException(
                            "OP_PUSHDATA4 length is too large"
                    );
                }

                requireScriptBytes(
                        scriptCode,
                        position,
                        (int) length
                );

                out.writeBytes(
                        java.util.Arrays.copyOfRange(
                                scriptCode,
                                position,
                                position + (int) length
                        )
                );

                position += (int) length;
            }
        }

        return out.toByteArray();
    }

    private static void requireScriptBytes(
            byte[] script,
            int offset,
            int length
    ) {
        if (offset < 0
                || length < 0
                || offset > script.length
                || length > script.length - offset) {

            throw new ScriptParseException(
                    "Truncated scriptCode"
            );
        }
    }
}