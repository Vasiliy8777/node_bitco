package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCleanStackTest {

    @Test
    void legacyWithoutCleanStackMustAllowExtraStackItems() {

        byte[] scriptSig = {
                (byte) Opcode.OP_1
        };

        byte[] scriptPubKey = {
                (byte) Opcode.OP_1
        };

        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.NONE
                )
        );
    }

    @Test
    void legacyWithCleanStackMustRejectExtraStackItems() {

        byte[] scriptSig = {
                (byte) Opcode.OP_1
        };

        byte[] scriptPubKey = {
                (byte) Opcode.OP_1
        };

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.CLEANSTACK
                )
        );
    }

    @Test
    void legacyWithCleanStackMustAcceptSingleTrueItem() {

        byte[] scriptSig =
                new byte[0];

        byte[] scriptPubKey = {
                (byte) Opcode.OP_1
        };

        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.CLEANSTACK
                )
        );
    }

    @Test
    void legacyWithCleanStackMustRejectSingleFalseItem() {

        byte[] scriptSig =
                new byte[0];

        byte[] scriptPubKey = {
                (byte) Opcode.OP_0
        };

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.CLEANSTACK
                )
        );
    }

    @Test
    void p2shWithoutCleanStackMustAllowExtraFinalStackItems() {

        /*
         * redeemScript:
         *
         * OP_1 OP_1
         *
         * После выполнения:
         *
         * [1, 1]
         *
         * top == true, но stack содержит 2 элемента.
         */
        byte[] redeemScript = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_1
        };

        byte[] scriptSig =
                pushOnly(
                        redeemScript
                );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        redeemScript
                );

        int flags =
                ScriptVerifyFlags.P2SH;

        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        flags
                )
        );
    }

    @Test
    void p2shWithCleanStackMustRejectExtraFinalStackItems() {

        byte[] redeemScript = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_1
        };

        byte[] scriptSig =
                pushOnly(
                        redeemScript
                );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        redeemScript
                );

        int flags =
                ScriptVerifyFlags.P2SH
                        | ScriptVerifyFlags.CLEANSTACK;

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        flags
                )
        );
    }

    @Test
    void p2shWithCleanStackMustAcceptSingleTrueFinalItem() {

        byte[] redeemScript = {
                (byte) Opcode.OP_1
        };

        byte[] scriptSig =
                pushOnly(
                        redeemScript
                );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        redeemScript
                );

        int flags =
                ScriptVerifyFlags.P2SH
                        | ScriptVerifyFlags.CLEANSTACK;

        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction(),
                        0,
                        scriptSig,
                        scriptPubKey,
                        flags
                )
        );
    }

    @Test
    void p2shOuterMustNotApplyCleanStack() {

        /*
         * Этот тест защищает nested witness path.
         *
         * scriptSig оставляет:
         *
         * [extra, redeemScript]
         *
         * После outer scriptPubKey:
         *
         * [extra, true]
         *
         * verifyP2shOuter() НЕ является финальной
         * script verification, поэтому CLEANSTACK
         * здесь применять нельзя.
         */

        byte[] redeemScript = {
                (byte) Opcode.OP_1
        };

        ByteArrayOutputStream scriptSig =
                new ByteArrayOutputStream();

        /*
         * extra item
         */
        scriptSig.write(
                Opcode.OP_1
        );

        /*
         * redeemScript
         */
        writePush(
                scriptSig,
                redeemScript
        );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        redeemScript
                );

        int flags =
                ScriptVerifyFlags.P2SH
                        | ScriptVerifyFlags.CLEANSTACK;

        assertTrue(
                LegacyScriptVerifier.verifyP2shOuter(
                        transaction(),
                        0,
                        scriptSig.toByteArray(),
                        scriptPubKey,
                        flags
                )
        );
    }

    private static byte[] p2shScriptPubKey(
            byte[] redeemScript
    ) {

        byte[] hash160 =
                Hash160.hash(
                        redeemScript
                );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        output.write(
                Opcode.OP_HASH160
        );

        output.write(
                hash160.length
        );

        output.writeBytes(
                hash160
        );

        output.write(
                Opcode.OP_EQUAL
        );

        return output.toByteArray();
    }

    private static byte[] pushOnly(
            byte[] data
    ) {

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        writePush(
                output,
                data
        );

        return output.toByteArray();
    }

    private static void writePush(
            ByteArrayOutputStream output,
            byte[] data
    ) {

        if (data.length
                > Opcode.OP_DATA_MAX) {

            throw new IllegalArgumentException(
                    "Test helper supports only direct pushes"
            );
        }

        output.write(
                data.length
        );

        output.writeBytes(
                data
        );
    }

    private static Transaction transaction() {

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x33
        );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                new Hash256(
                                        previousHash
                                ),
                                new UInt32(0)
                        ),
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output =
                new TxOut(
                        1000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        return new Transaction(
                2,
                List.of(
                        input
                ),
                List.of(
                        output
                ),
                new UInt32(0)
        );
    }
}