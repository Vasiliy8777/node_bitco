package ru.bitcoin.node.script;

import java.io.ByteArrayOutputStream;

public final class P2pkhScript {

    public static final int PUBLIC_KEY_HASH_LENGTH =
            20;

    private P2pkhScript() {
    }

    public static byte[] scriptPubKey(
            byte[] publicKeyHash
    ) {
        if (publicKeyHash == null) {
            throw new IllegalArgumentException(
                    "publicKeyHash must not be null"
            );
        }

        if (publicKeyHash.length
                != PUBLIC_KEY_HASH_LENGTH) {

            throw new IllegalArgumentException(
                    "P2PKH public key hash must contain exactly 20 bytes"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(25);

        out.write(
                Opcode.OP_DUP
        );

        out.write(
                Opcode.OP_HASH160
        );

        /*
         * Direct push of 20 bytes.
         */
        out.write(
                PUBLIC_KEY_HASH_LENGTH
        );

        out.writeBytes(
                publicKeyHash
        );

        out.write(
                Opcode.OP_EQUALVERIFY
        );

        out.write(
                Opcode.OP_CHECKSIG
        );

        return out.toByteArray();
    }

    public static byte[] scriptSig(
            byte[] signatureWithHashType,
            byte[] publicKey
    ) {
        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        writePushData(
                out,
                signatureWithHashType
        );

        writePushData(
                out,
                publicKey
        );

        return out.toByteArray();
    }

    private static void writePushData(
            ByteArrayOutputStream out,
            byte[] data
    ) {
        /*
         * Для стандартной P2PKH signature и public key
         * обе величины помещаются в direct push.
         *
         * Signature обычно <= 73 bytes,
         * compressed pubkey = 33 bytes.
         */
        if (data.length == 0) {

            out.write(
                    Opcode.OP_0
            );

            return;
        }

        if (data.length
                <= Opcode.OP_DATA_MAX) {

            out.write(
                    data.length
            );

            out.writeBytes(
                    data
            );

            return;
        }

        if (data.length <= 0xff) {

            out.write(
                    Opcode.OP_PUSHDATA1
            );

            out.write(
                    data.length
            );

            out.writeBytes(
                    data
            );

            return;
        }

        throw new IllegalArgumentException(
                "Push data is too large for P2PKH helper: "
                        + data.length
        );
    }
}