package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptPubKeyClassifierTest {

    @Test
    void p2pkhMustBeDetected() {

        byte[] script =
                new byte[25];

        script[0] =
                (byte) Opcode.OP_DUP;

        script[1] =
                (byte) Opcode.OP_HASH160;

        script[2] =
                0x14;

        for (int i = 3;
             i < 23;
             i++) {

            script[i] =
                    (byte) i;
        }

        script[23] =
                (byte) Opcode.OP_EQUALVERIFY;

        script[24] =
                (byte) Opcode.OP_CHECKSIG;

        assertEquals(
                ScriptPubKeyType.PUBKEYHASH,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void p2shMustBeDetected() {

        byte[] script =
                new byte[23];

        script[0] =
                (byte) Opcode.OP_HASH160;

        script[1] =
                0x14;

        for (int i = 2;
             i < 22;
             i++) {

            script[i] =
                    (byte) i;
        }

        script[22] =
                (byte) Opcode.OP_EQUAL;

        assertEquals(
                ScriptPubKeyType.SCRIPTHASH,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void compressedP2pkMustBeDetected() {

        byte[] script =
                new byte[35];

        script[0] =
                0x21;

        script[1] =
                0x02;

        for (int i = 2;
             i < 34;
             i++) {

            script[i] =
                    (byte) i;
        }

        script[34] =
                (byte) Opcode.OP_CHECKSIG;

        assertEquals(
                ScriptPubKeyType.PUBKEY,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void uncompressedP2pkMustBeDetected() {

        byte[] script =
                new byte[67];

        script[0] =
                0x41;

        script[1] =
                0x04;

        for (int i = 2;
             i < 66;
             i++) {

            script[i] =
                    (byte) i;
        }

        script[66] =
                (byte) Opcode.OP_CHECKSIG;

        assertEquals(
                ScriptPubKeyType.PUBKEY,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void nativeP2wpkhMustBeDetected() {

        byte[] script =
                new byte[22];

        script[0] =
                (byte) Opcode.OP_0;

        script[1] =
                0x14;

        assertEquals(
                ScriptPubKeyType.WITNESS_V0_KEYHASH,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void nativeP2wshMustBeDetected() {

        byte[] script =
                new byte[34];

        script[0] =
                (byte) Opcode.OP_0;

        script[1] =
                0x20;

        assertEquals(
                ScriptPubKeyType.WITNESS_V0_SCRIPTHASH,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void taprootMustBeDetected() {

        byte[] script =
                new byte[34];

        script[0] =
                (byte) Opcode.OP_1;

        script[1] =
                0x20;

        assertEquals(
                ScriptPubKeyType.WITNESS_V1_TAPROOT,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void unknownWitnessVersionMustBeDetected() {

        byte[] script =
                new byte[34];

        script[0] =
                (byte) Opcode.OP_2;

        script[1] =
                0x20;

        assertEquals(
                ScriptPubKeyType.WITNESS_UNKNOWN,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void invalidWitnessV0LengthMustBeNonStandard() {

        /*
         * OP_0 PUSH30 <30 bytes>
         *
         * structurally valid witness program,
         * but v0 recognizes only 20 or 32 bytes.
         */
        byte[] script =
                new byte[32];

        script[0] =
                (byte) Opcode.OP_0;

        script[1] =
                30;

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void opReturnMustBeDetected() {

        byte[] script = {
                (byte) Opcode.OP_RETURN,
                0x02,
                0x01,
                0x02
        };

        assertEquals(
                ScriptPubKeyType.NULL_DATA,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void arbitraryScriptMustBeNonStandard() {

        byte[] script = {
                (byte) Opcode.OP_DUP,
                (byte) Opcode.OP_DROP,
                (byte) Opcode.OP_1
        };

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

    @Test
    void emptyScriptMustBeNonStandard() {

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        new byte[0]
                )
        );
    }

    private static byte[] compressedKey(
            int prefix,
            int fill
    ) {
        byte[] key =
                new byte[33];

        key[0] =
                (byte) prefix;

        for (int i = 1;
             i < key.length;
             i++) {

            key[i] =
                    (byte) fill;
        }

        return key;
    }

    private static byte[] bareTwoOfThreeMultisig() {

        byte[] key1 =
                compressedKey(
                        0x02,
                        0x11
                );

        byte[] key2 =
                compressedKey(
                        0x03,
                        0x22
                );

        byte[] key3 =
                compressedKey(
                        0x02,
                        0x33
                );

        byte[] script =
                new byte[
                        1
                                + 34
                                + 34
                                + 34
                                + 1
                                + 1
                        ];

        int offset = 0;

        script[offset++] =
                (byte) Opcode.OP_2;

        script[offset++] =
                33;

        System.arraycopy(
                key1,
                0,
                script,
                offset,
                key1.length
        );

        offset +=
                key1.length;

        script[offset++] =
                33;

        System.arraycopy(
                key2,
                0,
                script,
                offset,
                key2.length
        );

        offset +=
                key2.length;

        script[offset++] =
                33;

        System.arraycopy(
                key3,
                0,
                script,
                offset,
                key3.length
        );

        offset +=
                key3.length;

        script[offset++] =
                (byte) Opcode.OP_3;

        script[offset] =
                (byte) Opcode.OP_CHECKMULTISIG;

        return script;
    }
    @Test
    void bareTwoOfThreeMultisigMustBeDetected() {

        assertEquals(
                ScriptPubKeyType.MULTISIG,
                ScriptPubKeyClassifier.classify(
                        bareTwoOfThreeMultisig()
                )
        );
    }
    @Test
    void bareOneOfOneMultisigMustBeDetected() {

        byte[] key =
                compressedKey(
                        0x02,
                        0x44
                );

        byte[] script =
                new byte[1 + 34 + 1 + 1];

        int offset = 0;

        script[offset++] =
                (byte) Opcode.OP_1;

        script[offset++] =
                33;

        System.arraycopy(
                key,
                0,
                script,
                offset,
                key.length
        );

        offset +=
                key.length;

        script[offset++] =
                (byte) Opcode.OP_1;

        script[offset] =
                (byte) Opcode.OP_CHECKMULTISIG;

        assertEquals(
                ScriptPubKeyType.MULTISIG,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }
    @Test
    void multisigWithRequiredGreaterThanTotalMustBeNonStandard() {

        byte[] script =
                bareTwoOfThreeMultisig();

        script[0] =
                (byte) Opcode.OP_4;

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }
    @Test
    void multisigWithWrongDeclaredKeyCountMustBeNonStandard() {

        byte[] script =
                bareTwoOfThreeMultisig();

        /*
         * Фактически 3 ключа,
         * но объявляем N = 2.
         */
        script[script.length - 2] =
                (byte) Opcode.OP_2;

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }
    @Test
    void multisigWithInvalidPubkeyEncodingMustBeNonStandard() {

        byte[] script =
                bareTwoOfThreeMultisig();

        /*
         * Первый byte первого pubkey.
         *
         * script:
         * OP_2
         * PUSH33
         * <pubkey>
         *
         * поэтому prefix находится по index 2.
         */
        script[2] =
                0x05;

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }
    @Test
    void p2pkWithInvalidCompressedPrefixMustBeNonStandard() {

        byte[] script =
                new byte[35];

        script[0] =
                0x21;

        script[1] =
                0x05;

        script[34] =
                (byte) Opcode.OP_CHECKSIG;

        assertEquals(
                ScriptPubKeyType.NONSTANDARD,
                ScriptPubKeyClassifier.classify(
                        script
                )
        );
    }

}