package ru.bitcoin.node.script;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;

public final class WitnessV0SignatureHash {

    private static final byte[] ZERO_HASH =
            new byte[32];

    private WitnessV0SignatureHash() {
    }

    public static byte[] calculate(
            Transaction transaction,
            int inputIndex,
            byte[] scriptCode,
            long amount,
            int hashType
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        if (scriptCode == null) {
            throw new IllegalArgumentException(
                    "scriptCode must not be null"
            );
        }

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must not be negative"
            );
        }

        int baseType =
                SignatureHashType.baseType(
                        hashType
                );

        boolean anyoneCanPay =
                SignatureHashType.isAnyoneCanPay(
                        hashType
                );

        byte[] hashPrevouts =
                calculateHashPrevouts(
                        transaction,
                        anyoneCanPay
                );

        byte[] hashSequence =
                calculateHashSequence(
                        transaction,
                        anyoneCanPay,
                        baseType
                );

        byte[] hashOutputs =
                calculateHashOutputs(
                        transaction,
                        inputIndex,
                        baseType
                );

        TxIn input =
                transaction.inputs()
                        .get(inputIndex);

        ByteArrayOutputStream preimage =
                new ByteArrayOutputStream();

        /*
         * 1. nVersion
         */
        write(
                preimage,
                LittleEndian.int32(
                        transaction.version()
                )
        );

        /*
         * 2. hashPrevouts
         */
        write(
                preimage,
                hashPrevouts
        );

        /*
         * 3. hashSequence
         */
        write(
                preimage,
                hashSequence
        );

        /*
         * 4. current input outpoint
         */
        writeOutPoint(
                preimage,
                input.previousOutput()
        );

        /*
         * 5. scriptCode serialized like a script
         * inside CTxOut:
         *
         * CompactSize length + script bytes.
         */
        write(
                preimage,
                CompactSize.encode(
                        scriptCode.length
                )
        );

        write(
                preimage,
                scriptCode
        );

        /*
         * 6. amount of the UTXO being spent
         */
        write(
                preimage,
                LittleEndian.int64(
                        amount
                )
        );

        /*
         * 7. current input nSequence
         */
        write(
                preimage,
                LittleEndian.uint32(
                        input.sequence().value()
                )
        );

        /*
         * 8. hashOutputs
         */
        write(
                preimage,
                hashOutputs
        );

        /*
         * 9. nLockTime
         */
        write(
                preimage,
                LittleEndian.uint32(
                        transaction.lockTime().value()
                )
        );

        /*
         * 10. nHashType as 4-byte little endian.
         */
        write(
                preimage,
                LittleEndian.int32(
                        hashType
                )
        );

        return Hash256Digest.hashBytes(
                preimage.toByteArray()
        );
    }

    private static byte[] calculateHashPrevouts(
            Transaction transaction,
            boolean anyoneCanPay
    ) {
        if (anyoneCanPay) {
            return ZERO_HASH.clone();
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        for (TxIn input : transaction.inputs()) {

            writeOutPoint(
                    out,
                    input.previousOutput()
            );
        }

        return Hash256Digest.hashBytes(
                out.toByteArray()
        );
    }

    private static byte[] calculateHashSequence(
            Transaction transaction,
            boolean anyoneCanPay,
            int baseType
    ) {
        if (anyoneCanPay
                || baseType
                == SignatureHashType.SIGHASH_SINGLE
                || baseType
                == SignatureHashType.SIGHASH_NONE) {

            return ZERO_HASH.clone();
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        for (TxIn input : transaction.inputs()) {

            write(
                    out,
                    LittleEndian.uint32(
                            input.sequence().value()
                    )
            );
        }

        return Hash256Digest.hashBytes(
                out.toByteArray()
        );
    }

    private static byte[] calculateHashOutputs(
            Transaction transaction,
            int inputIndex,
            int baseType
    ) {
        if (baseType
                != SignatureHashType.SIGHASH_SINGLE
                && baseType
                != SignatureHashType.SIGHASH_NONE) {

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            for (TxOut output : transaction.outputs()) {

                writeOutput(
                        out,
                        output
                );
            }

            return Hash256Digest.hashBytes(
                    out.toByteArray()
            );
        }

        if (baseType
                == SignatureHashType.SIGHASH_SINGLE
                && inputIndex
                < transaction.outputs().size()) {

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            writeOutput(
                    out,
                    transaction.outputs()
                            .get(inputIndex)
            );

            return Hash256Digest.hashBytes(
                    out.toByteArray()
            );
        }

        /*
         * SIGHASH_NONE
         * or SIGHASH_SINGLE with no corresponding output.
         *
         * BIP143 does NOT use the legacy HASH_ONE bug here.
         */
        return ZERO_HASH.clone();
    }

    private static void writeOutPoint(
            ByteArrayOutputStream out,
            OutPoint outPoint
    ) {
        /*
         * Hash256.bytes() already contains the raw
         * transaction-id bytes used by wire serialization.
         */
        write(
                out,
                outPoint.transactionId()
                        .bytes()
        );

        write(
                out,
                LittleEndian.uint32(
                        outPoint.outputIndex()
                                .value()
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

        byte[] scriptPubKey =
                output.scriptPubKey();

        write(
                out,
                CompactSize.encode(
                        scriptPubKey.length
                )
        );

        write(
                out,
                scriptPubKey
        );
    }

    private static void write(
            ByteArrayOutputStream out,
            byte[] bytes
    ) {
        out.writeBytes(bytes);
    }
}