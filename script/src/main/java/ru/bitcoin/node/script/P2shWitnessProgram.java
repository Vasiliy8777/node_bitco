package ru.bitcoin.node.script;

import java.util.List;
import java.util.Optional;

public final class P2shWitnessProgram {

    private P2shWitnessProgram() {
    }

    public static Optional<WitnessProgram> extract(
            byte[] scriptSig
    ) {
        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        final List<ScriptInstruction> instructions;

        try {
            instructions =
                    ScriptParser.parse(
                            scriptSig
                    );
        } catch (ScriptParseException e) {
            return Optional.empty();
        }

        /*
         * P2SH-wrapped witness:
         *
         * scriptSig должен состоять РОВНО
         * из одного push witnessProgram.
         */
        if (instructions.size() != 1) {
            return Optional.empty();
        }

        ScriptInstruction instruction =
                instructions.getFirst();

        if (!instruction.isPushData()) {
            return Optional.empty();
        }

        byte[] redeemScript =
                instruction.data();

        if (redeemScript == null) {
            return Optional.empty();
        }

        /*
         * Witness program имеет длину 4..42.
         * Поэтому canonical nested scriptSig:
         *
         * <direct-push-length> <redeemScript>
         *
         * Например P2SH-P2WPKH:
         *
         * 16 00 14 <20 bytes>
         *
         * где 0x16 = 22 bytes.
         */
        if (scriptSig.length
                != redeemScript.length + 1) {

            return Optional.empty();
        }

        if (Byte.toUnsignedInt(
                scriptSig[0]
        ) != redeemScript.length) {

            return Optional.empty();
        }

        return WitnessProgram.parse(
                redeemScript
        );
    }

    public static byte[] redeemScript(
            byte[] scriptSig
    ) {
        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        Optional<WitnessProgram> program =
                extract(
                        scriptSig
                );

        if (program.isEmpty()) {
            throw new IllegalArgumentException(
                    "scriptSig does not contain an exact wrapped witness program"
            );
        }

        byte[] result =
                new byte[
                        scriptSig.length - 1
                        ];

        System.arraycopy(
                scriptSig,
                1,
                result,
                0,
                result.length
        );

        return result;
    }
}