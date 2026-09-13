package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class P2shWitnessProgramTest {

    @Test
    void exactP2wpkhPushMustBeRecognized() {

        byte[] program =
                new byte[20];

        Arrays.fill(
                program,
                (byte) 0x11
        );

        byte[] redeemScript =
                new byte[22];

        redeemScript[0] =
                (byte) Opcode.OP_0;

        redeemScript[1] =
                20;

        System.arraycopy(
                program,
                0,
                redeemScript,
                2,
                20
        );

        byte[] scriptSig =
                directPush(
                        redeemScript
                );

        WitnessProgram result =
                P2shWitnessProgram.extract(
                        scriptSig
                ).orElseThrow();

        assertTrue(
                result.isP2wpkh()
        );
    }

    @Test
    void exactP2wshPushMustBeRecognized() {

        byte[] program =
                new byte[32];

        Arrays.fill(
                program,
                (byte) 0x22
        );

        byte[] redeemScript =
                new byte[34];

        redeemScript[0] =
                (byte) Opcode.OP_0;

        redeemScript[1] =
                32;

        System.arraycopy(
                program,
                0,
                redeemScript,
                2,
                32
        );

        WitnessProgram result =
                P2shWitnessProgram.extract(
                        directPush(
                                redeemScript
                        )
                ).orElseThrow();

        assertTrue(
                result.isP2wsh()
        );
    }

    @Test
    void additionalPushMustNotBeAccepted() {

        byte[] redeemScript =
                createP2wpkhRedeemScript();

        byte[] scriptSig =
                new byte[
                        1
                                + redeemScript.length
                                + 2
                        ];

        scriptSig[0] =
                (byte)
                        redeemScript.length;

        System.arraycopy(
                redeemScript,
                0,
                scriptSig,
                1,
                redeemScript.length
        );

        /*
         * Дополнительный push:
         * 01 01
         */
        scriptSig[
                1 + redeemScript.length
                ] =
                1;

        scriptSig[
                2 + redeemScript.length
                ] =
                1;

        assertTrue(
                P2shWitnessProgram.extract(
                        scriptSig
                ).isEmpty()
        );
    }

    @Test
    void pushData1MustNotBeAccepted() {

        byte[] redeemScript =
                createP2wpkhRedeemScript();

        byte[] scriptSig =
                new byte[
                        2
                                + redeemScript.length
                        ];

        scriptSig[0] =
                (byte)
                        Opcode.OP_PUSHDATA1;

        scriptSig[1] =
                (byte)
                        redeemScript.length;

        System.arraycopy(
                redeemScript,
                0,
                scriptSig,
                2,
                redeemScript.length
        );

        assertTrue(
                P2shWitnessProgram.extract(
                        scriptSig
                ).isEmpty()
        );
    }

    @Test
    void nonWitnessRedeemScriptMustNotBeAccepted() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        assertTrue(
                P2shWitnessProgram.extract(
                        directPush(
                                redeemScript
                        )
                ).isEmpty()
        );
    }

    private static byte[] createP2wpkhRedeemScript() {

        byte[] result =
                new byte[22];

        result[0] =
                (byte) Opcode.OP_0;

        result[1] =
                20;

        Arrays.fill(
                result,
                2,
                result.length,
                (byte) 0x33
        );

        return result;
    }

    private static byte[] directPush(
            byte[] data
    ) {

        byte[] result =
                new byte[
                        1 + data.length
                        ];

        result[0] =
                (byte)
                        data.length;

        System.arraycopy(
                data,
                0,
                result,
                1,
                data.length
        );

        return result;
    }
}