package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.hash.Hash160;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class P2shScriptTest {

    @Test
    void exactP2shTemplateShouldBeDetected() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        byte[] hash =
                Hash160.hash(
                        redeemScript
                );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        hash
                );

        assertTrue(
                P2shScript.isPayToScriptHash(
                        scriptPubKey
                )
        );
    }

    @Test
    void wrongLengthMustNotBeDetectedAsP2sh() {

        byte[] script =
                new byte[]{
                        (byte) Opcode.OP_HASH160,
                        0x01,
                        0x01,
                        (byte) Opcode.OP_EQUAL
                };

        assertFalse(
                P2shScript.isPayToScriptHash(
                        script
                )
        );
    }

    @Test
    void nonMinimalHashPushMustNotBeDetectedAsP2sh() {

        byte[] hash =
                new byte[20];

        /*
         * HASH160
         * PUSHDATA1 20
         * <20 bytes>
         * EQUAL
         *
         * Семантически похоже, но это НЕ
         * BIP16 P2SH template.
         */
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                Opcode.OP_HASH160
        );

        out.write(
                Opcode.OP_PUSHDATA1
        );

        out.write(
                20
        );

        out.writeBytes(
                hash
        );

        out.write(
                Opcode.OP_EQUAL
        );

        assertFalse(
                P2shScript.isPayToScriptHash(
                        out.toByteArray()
                )
        );
    }

    @Test
    void pushOnlyScriptShouldPass() {

        byte[] script =
                new byte[]{
                        0x02,
                        0x11,
                        0x22,

                        (byte) Opcode.OP_0,
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_16
                };

        assertTrue(
                P2shScript.isPushOnly(
                        script
                )
        );
    }

    @Test
    void pushDataOpcodesShouldBePushOnly() {

        byte[] script =
                new byte[]{
                        (byte) Opcode.OP_PUSHDATA1,
                        0x02,
                        0x11,
                        0x22
                };

        assertTrue(
                P2shScript.isPushOnly(
                        script
                )
        );
    }

    @Test
    void nonPushOpcodeShouldFailPushOnly() {

        byte[] script =
                new byte[]{
                        (byte) Opcode.OP_DUP
                };

        assertFalse(
                P2shScript.isPushOnly(
                        script
                )
        );
    }

    private static byte[] p2shScriptPubKey(
            byte[] hash
    ) {

        if (hash.length != 20) {
            throw new IllegalArgumentException();
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                Opcode.OP_HASH160
        );

        out.write(
                20
        );

        out.writeBytes(
                hash
        );

        out.write(
                Opcode.OP_EQUAL
        );

        return out.toByteArray();
    }
}