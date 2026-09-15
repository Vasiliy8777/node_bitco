package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P2shWitnessWrapperRegressionTest {
    @Test
    void malformedWitnessWrappersCannotFallBackToLegacyWithEmptyWitness() {
        for (int version : new int[]{0, 0x51, 0x52}) {
            byte[] redeem = new byte[22];
            redeem[0] = (byte) version;
            redeem[1] = 20;
            Arrays.fill(redeem, 2, redeem.length, (byte) 1);
            byte[] direct = concat(new byte[]{22}, redeem);
            for (byte[] wrapper : List.of(concat(new byte[]{0x4c, 22}, redeem),
                    concat(new byte[]{0x4d, 22, 0}, redeem),
                    concat(new byte[]{0x4e, 22, 0, 0, 0}, redeem), concat(new byte[]{0}, direct))) {
                var tx = transaction(wrapper);
                var view = view(redeem);
                assertDoesNotThrow(() -> InputScriptValidator.validateAll(tx, view, ScriptVerifyFlags.P2SH));
                assertThrows(TransactionValidationException.class, () -> InputScriptValidator.validateAll(tx, view,
                        ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS));
            }
        }
    }

    @Test
    void canonicalUnknownVersionRemainsValidButV0NeedsWitness() {
        byte[] unknown = new byte[]{0x52, 2, 1, 1};
        assertDoesNotThrow(() -> InputScriptValidator.validateAll(transaction(concat(new byte[]{4}, unknown)),
                view(unknown), ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS));
        byte[] v0 = new byte[22];
        v0[1] = 20;
        assertThrows(TransactionValidationException.class, () -> InputScriptValidator.validateAll(
                transaction(concat(new byte[]{22}, v0)), view(v0), ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS));
    }

    private static UtxoView view(byte[] redeem) {
        byte[] script = concat(concat(new byte[]{(byte) 0xa9, 20}, Hash160.hash(redeem)), new byte[]{(byte) 0x87});
        return point -> Optional.of(new UtxoEntry(1000, script, 1, false));
    }
    private static Transaction transaction(byte[] wrapper) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)),
                wrapper, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
