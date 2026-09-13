package ru.bitcoin.node.script;

import java.util.List;

public final class P2shScript {

    public static final int SCRIPT_HASH_LENGTH =
            20;

    private P2shScript() {
    }

    public static boolean isPayToScriptHash(
            byte[] scriptPubKey
    ) {
        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        /*
         * BIP16 P2SH template is EXACTLY:
         *
         * OP_HASH160
         * 0x14
         * <20 bytes>
         * OP_EQUAL
         *
         * Total = 23 bytes.
         */
        return scriptPubKey.length == 23
                && Byte.toUnsignedInt(
                scriptPubKey[0]
        ) == Opcode.OP_HASH160
                && Byte.toUnsignedInt(
                scriptPubKey[1]
        ) == SCRIPT_HASH_LENGTH
                && Byte.toUnsignedInt(
                scriptPubKey[22]
        ) == Opcode.OP_EQUAL;
    }

    public static boolean isPushOnly(
            byte[] script
    ) {
        if (script == null) {
            throw new IllegalArgumentException(
                    "script must not be null"
            );
        }

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        script
                );

        for (ScriptInstruction instruction
                : instructions) {

            int opcode =
                    instruction.opcode();

            /*
             * Bitcoin Core IsPushOnly:
             *
             * opcode > OP_16 => false.
             *
             * Поэтому сюда входят:
             * direct pushes,
             * PUSHDATA1/2/4,
             * OP_0,
             * OP_1NEGATE,
             * OP_1 ... OP_16.
             */
            if (opcode > Opcode.OP_16) {
                return false;
            }
        }

        return true;
    }
}