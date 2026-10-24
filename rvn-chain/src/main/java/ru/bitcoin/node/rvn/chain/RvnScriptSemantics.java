package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.rvn.chain.assets.RvnHistoricalAssetCodec;

/** RVN-specific GetOp/IsUnspendable differences, independent of Bitcoin scripts. */
public final class RvnScriptSemantics {
    private RvnScriptSemantics() {}
    public static boolean unspendable(byte[] script) {
        if (script.length > 10000 || (script.length > 0 && (script[0] == 0x6a || (script[0] & 255) == 0xc0))) return true;
        try {
            // Core's GetAssetAmountFromScript uses the classified offset even
            // before the separate TransferAssetFromScript offset-fix deployment.
            return RvnHistoricalAssetCodec.decode(script, true).map(asset -> asset.amount() == 0).orElse(false);
        } catch (IllegalArgumentException e) { return false; } // Core catches deserialize failure.
    }
    public static long sigops(byte[] script, boolean accurate) {
        long count = 0; int position = 0, previous = 0;
        while (position < script.length) {
            int opcode = script[position++] & 255;
            if (opcode == 0xc0) break; // RVN GetOp consumes the complete remainder.
            long length = opcode <= 75 ? opcode : 0;
            if (opcode >= 76 && opcode <= 78) {
                int size = 1 << (opcode - 76);
                if (size > script.length - position) break;
                for (int i = 0; i < size; i++) length |= (script[position++] & 255L) << (8 * i);
            }
            if (length > script.length - position) break;
            position += (int) length;
            if (opcode == 0xac || opcode == 0xad) count++;
            if (opcode == 0xae || opcode == 0xaf) count += accurate && previous >= 0x51 && previous <= 0x60 ? previous - 0x50 : 20;
            previous = opcode;
        }
        return count;
    }
}
