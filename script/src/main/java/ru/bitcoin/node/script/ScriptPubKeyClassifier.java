package ru.bitcoin.node.script;

import java.util.Optional;

public final class ScriptPubKeyClassifier {

    private ScriptPubKeyClassifier() {
    }

    public static ScriptPubKeyType classify(
            byte[] scriptPubKey
    ) {
        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        if (isP2pkh(scriptPubKey)) {
            return ScriptPubKeyType.PUBKEYHASH;
        }

        if (P2shScript.isPayToScriptHash(
                scriptPubKey
        )) {
            return ScriptPubKeyType.SCRIPTHASH;
        }

        ScriptPubKeyType witnessType =
                classifyWitnessProgram(
                        scriptPubKey
                );

        if (witnessType != null) {
            return witnessType;
        }

        if (isP2pk(scriptPubKey)) {
            return ScriptPubKeyType.PUBKEY;
        }

        if (isBareMultisig(scriptPubKey)) {
            return ScriptPubKeyType.MULTISIG;
        }

        if (isBareMultisig(scriptPubKey)) {
            return ScriptPubKeyType.MULTISIG;
        }

        if (isNullData(scriptPubKey)) {
            return ScriptPubKeyType.NULL_DATA;
        }

        return ScriptPubKeyType.NONSTANDARD;
    }

    private static boolean isBareMultisig(
            byte[] script
    ) {
        final java.util.List<ScriptInstruction> instructions;

        try {
            instructions =
                    ScriptParser.parse(
                            script
                    );
        } catch (RuntimeException e) {
            return false;
        }

        /*
         * Минимальная форма:
         *
         * OP_1
         * <pubkey>
         * OP_1
         * OP_CHECKMULTISIG
         *
         * = 4 instructions.
         */
        if (instructions.size() < 4) {
            return false;
        }

        ScriptInstruction requiredInstruction =
                instructions.get(0);

        ScriptInstruction totalInstruction =
                instructions.get(
                        instructions.size() - 2
                );

        ScriptInstruction checkMultisigInstruction =
                instructions.get(
                        instructions.size() - 1
                );

        int requiredSignatures =
                decodeSmallIntegerOpcode(
                        requiredInstruction.opcode()
                );

        int totalPublicKeys =
                decodeSmallIntegerOpcode(
                        totalInstruction.opcode()
                );

        if (requiredSignatures < 1
                || totalPublicKeys < 1) {
            return false;
        }

        if (requiredSignatures > totalPublicKeys) {
            return false;
        }

        /*
         * Bare multisig standard Solver form uses
         * OP_1..OP_16 for M and N.
         */
        if (totalPublicKeys > 16) {
            return false;
        }

        if (checkMultisigInstruction.opcode()
                != Opcode.OP_CHECKMULTISIG) {

            return false;
        }

        /*
         * Между OP_M и OP_N должно быть ровно N pubkeys.
         */
        int actualPublicKeyCount =
                instructions.size() - 3;

        if (actualPublicKeyCount
                != totalPublicKeys) {

            return false;
        }

        for (int i = 1;
             i <= actualPublicKeyCount;
             i++) {

            ScriptInstruction instruction =
                    instructions.get(i);

            if (!instruction.isPushData()) {
                return false;
            }

            byte[] publicKey =
                    instruction.data();

            if (!isStandardPublicKeyEncoding(
                    publicKey
            )) {
                return false;
            }
        }

        return true;
    }

    private static int decodeSmallIntegerOpcode(
            int opcode
    ) {
        if (opcode == Opcode.OP_0) {
            return 0;
        }

        if (opcode >= Opcode.OP_1
                && opcode <= Opcode.OP_16) {

            return opcode
                    - Opcode.OP_1
                    + 1;
        }

        return -1;
    }

    private static boolean isStandardPublicKeyEncoding(
            byte[] publicKey
    ) {
        if (publicKey == null) {
            return false;
        }

        if (publicKey.length == 33) {
            int prefix =
                    Byte.toUnsignedInt(
                            publicKey[0]
                    );

            return prefix == 0x02
                    || prefix == 0x03;
        }

        if (publicKey.length == 65) {
            return Byte.toUnsignedInt(
                    publicKey[0]
            ) == 0x04;
        }

        return false;
    }

    private static boolean isP2pkh(
            byte[] script
    ) {
        /*
         * OP_DUP
         * OP_HASH160
         * PUSH20
         * <20-byte pubKeyHash>
         * OP_EQUALVERIFY
         * OP_CHECKSIG
         *
         * 25 bytes total.
         */
        return script.length == 25

                && unsigned(script[0])
                == Opcode.OP_DUP

                && unsigned(script[1])
                == Opcode.OP_HASH160

                && unsigned(script[2])
                == 0x14

                && unsigned(script[23])
                == Opcode.OP_EQUALVERIFY

                && unsigned(script[24])
                == Opcode.OP_CHECKSIG;
    }

    private static boolean isP2pk(
            byte[] script
    ) {
        /*
         * Compressed pubkey:
         *
         * PUSH33
         * 02/03 <32 bytes>
         * OP_CHECKSIG
         */
        if (script.length == 35
                && unsigned(script[0]) == 0x21
                && unsigned(script[34])
                == Opcode.OP_CHECKSIG) {

            int prefix =
                    unsigned(
                            script[1]
                    );

            return prefix == 0x02
                    || prefix == 0x03;
        }

        /*
         * Uncompressed pubkey:
         *
         * PUSH65
         * 04 <64 bytes>
         * OP_CHECKSIG
         */
        return script.length == 67
                && unsigned(script[0]) == 0x41
                && unsigned(script[1]) == 0x04
                && unsigned(script[66])
                == Opcode.OP_CHECKSIG;
    }

    private static ScriptPubKeyType classifyWitnessProgram(
            byte[] script
    ) {
        Optional<WitnessProgram> witnessProgramOptional =
                WitnessProgram.parse(
                        script
                );

        if (witnessProgramOptional.isEmpty()) {
            return null;
        }

        WitnessProgram witnessProgram =
                witnessProgramOptional.get();

        int version =
                witnessProgram.version();

        int programLength =
                witnessProgram.programLength();

        if (version == 0) {

            if (programLength == 20) {
                return ScriptPubKeyType.WITNESS_V0_KEYHASH;
            }

            if (programLength == 32) {
                return ScriptPubKeyType.WITNESS_V0_SCRIPTHASH;
            }

            return ScriptPubKeyType.NONSTANDARD;
        }

        if (version == 1
                && programLength == 32) {

            return ScriptPubKeyType.WITNESS_V1_TAPROOT;
        }

        return ScriptPubKeyType.WITNESS_UNKNOWN;
    }

    private static boolean isNullData(
            byte[] script
    ) {
        /*
         * На этом этапе определяем только сам
         * тип NULL_DATA:
         *
         * script начинается с OP_RETURN.
         *
         * Ограничение размера datacarrier и
         * push-only содержимого добавим отдельно
         * на policy-уровне.
         */
        return script.length > 0
                && unsigned(script[0])
                == Opcode.OP_RETURN;
    }

    private static int unsigned(
            byte value
    ) {
        return value & 0xff;
    }
}