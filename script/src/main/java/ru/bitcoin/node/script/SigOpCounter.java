package ru.bitcoin.node.script;

import java.util.Arrays;
import java.util.Objects;

/** Counts opcodes without executing scripts, stopping at a malformed push like GetOp. */
public final class SigOpCounter {
    private SigOpCounter() { }

    public static long count(byte[] script, boolean accurate) {
        Objects.requireNonNull(script, "script");
        long count = 0;
        int previous = 0;
        int position = 0;
        while (position < script.length) {
            int opcode = script[position] & 0xff;
            int next = nextPosition(script, position);
            if (next < 0) break;
            if (opcode == Opcode.OP_CHECKSIG || opcode == Opcode.OP_CHECKSIGVERIFY) count++;
            if (opcode == Opcode.OP_CHECKMULTISIG || opcode == Opcode.OP_CHECKMULTISIGVERIFY) {
                count += accurate && previous >= Opcode.OP_1 && previous <= Opcode.OP_16
                        ? previous - Opcode.OP_1 + 1 : 20;
            }
            previous = opcode;
            position = next;
        }
        return count;
    }

    /** Last pushed value of a push-only script, or null for malformed/non-push scripts. */
    public static byte[] lastPush(byte[] script) {
        byte[] result = null;
        int position = 0;
        while (position < script.length) {
            int opcode = script[position] & 0xff;
            int next = nextPosition(script, position);
            if (next < 0 || opcode > Opcode.OP_16) return null;
            if (opcode <= 78) {
                int prefix = opcode < 76 ? 1 : opcode == 76 ? 2 : opcode == 77 ? 3 : 5;
                result = Arrays.copyOfRange(script, position + prefix, next);
            } else if (opcode == Opcode.OP_1NEGATE) {
                result = new byte[]{(byte) 0x81};
            } else if (opcode >= Opcode.OP_1) {
                result = new byte[]{(byte) (opcode - Opcode.OP_1 + 1)};
            } else {
                return null; // OP_RESERVED cannot produce a valid redeem script.
            }
            position = next;
        }
        return result;
    }

    private static int nextPosition(byte[] script, int position) {
        int opcode = script[position++] & 0xff;
        long size = opcode <= 75 ? opcode : 0;
        if (opcode >= 76 && opcode <= 78) {
            int lengthBytes = 1 << (opcode - 76);
            if (script.length - position < lengthBytes) return -1;
            for (int i = 0; i < lengthBytes; i++) size |= (script[position++] & 0xffL) << (8 * i);
        }
        return size > script.length - position ? -1 : position + (int) size;
    }
}
