package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.ByteArrayOutputStream;

public final class Bip34Validator {

    private static final int OP_0 = 0x00;
    private static final int OP_1 = 0x51;

    private Bip34Validator() {
    }

    public static void validate(
            Transaction coinbase,
            long blockHeight,
            NetworkParameters networkParameters
    ) {
        if (coinbase == null) {
            throw new IllegalArgumentException(
                    "coinbase must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        /*
         * До активации BIP34 правило не применяется.
         */
        if (blockHeight < networkParameters.bip34Height()) {
            return;
        }

        if (!coinbase.isCoinbase()) {
            throw new BlockValidationException(
                    "BIP34 validation requires coinbase transaction"
            );
        }

        byte[] expectedPrefix =
                encodeHeightPrefix(
                        blockHeight
                );

        byte[] scriptSig =
                coinbase.inputs()
                        .get(0)
                        .scriptSig();

        if (scriptSig.length < expectedPrefix.length) {
            throw new BlockValidationException(
                    "BIP34 violation: coinbase scriptSig is too short"
            );
        }

        for (int i = 0;
             i < expectedPrefix.length;
             i++) {

            if (scriptSig[i] != expectedPrefix[i]) {
                throw new BlockValidationException(
                        "BIP34 violation: block height mismatch in coinbase"
                );
            }
        }
    }

    static byte[] encodeHeightPrefix(
            long blockHeight
    ) {
        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        /*
         * Bitcoin Script integer opcodes:
         *
         * 0      -> OP_0
         * 1..16  -> OP_1 .. OP_16
         */
        if (blockHeight == 0) {
            return new byte[]{
                    (byte) OP_0
            };
        }

        if (blockHeight >= 1
                && blockHeight <= 16) {

            return new byte[]{
                    (byte) (
                            OP_1
                                    + blockHeight
                                    - 1
                    )
            };
        }

        byte[] encodedNumber =
                encodeScriptNumber(
                        blockHeight
                );

        /*
         * Block heights are tiny compared with the maximum
         * direct-push size. For all realistic Bitcoin heights,
         * this is a direct push:
         *
         * <length> <little-endian script number>
         */
        if (encodedNumber.length > 75) {
            throw new IllegalArgumentException(
                    "Encoded block height is too large"
            );
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        output.write(
                encodedNumber.length
        );

        output.writeBytes(
                encodedNumber
        );

        return output.toByteArray();
    }

    private static byte[] encodeScriptNumber(
            long value
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "value must not be negative"
            );
        }

        if (value == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        long remaining =
                value;

        while (remaining != 0) {
            output.write(
                    (int) (
                            remaining & 0xFF
                    )
            );

            remaining >>>= 8;
        }

        byte[] bytes =
                output.toByteArray();

        /*
         * В CScriptNum старший бит последнего байта —
         * это sign bit.
         *
         * Поэтому положительное число, у которого этот
         * бит уже установлен, получает дополнительный
         * нулевой байт.
         *
         * Например:
         *
         * 128 -> 80 00
         */
        if ((bytes[bytes.length - 1] & 0x80) != 0) {

            byte[] extended =
                    new byte[
                            bytes.length + 1
                            ];

            System.arraycopy(
                    bytes,
                    0,
                    extended,
                    0,
                    bytes.length
            );

            extended[
                    extended.length - 1
                    ] = 0x00;

            return extended;
        }

        return bytes;
    }
}