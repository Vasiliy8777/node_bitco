package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class SigPushOnlyTest {
    @Test void historicalTaprootExceptionStillEnforcesCltv() {
        var hash = Hash256.fromDisplayHex("0000000000000000000f14c35b2d841e986ab5441de8c585d5ffe55ea1e395ad");
        int flags = ru.bitcoin.node.consensus.script.ConsensusScriptFlags.forBlock(709632, hash,
                ru.bitcoin.node.protocol.network.NetworkParametersRegistry.mainnet(), true);
        byte[] script = {0x51, (byte) 0xb1, 0x75, 0x51}; // Require locktime >= 1.
        var tx = transaction(new byte[0]); // locktime = 0
        assertTrue(LegacyScriptVerifier.verify(tx, 0, new byte[0], script,
                ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS));
        UtxoView coins = point -> Optional.of(new UtxoEntry(2000, script, 1, false));
        assertThrows(TransactionValidationException.class,
                () -> InputScriptValidator.validateAll(tx, coins, flags));
    }
    private static final byte[] TRUE = {0x51};
    private static Transaction transaction(byte[] script) {
        byte[] hash = new byte[32]; hash[0] = 1;
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(hash), new UInt32(0)),
                script, new UInt32(0xffffffffL))), List.of(new TxOut(1000, TRUE)), new UInt32(0));
    }
    private static boolean verify(byte[] script, int flags) {
        return LegacyScriptVerifier.verify(transaction(script), 0, script, TRUE, flags);
    }
    @Test void nonPushScriptRemainsValidUnlessFlagIsEnabled() {
        byte[] script = {0x51, 0x75}; // OP_1 OP_DROP
        assertTrue(verify(script, ScriptVerifyFlags.NONE));
        assertFalse(verify(script, ScriptVerifyFlags.SIGPUSHONLY));
        UtxoView coins = point -> Optional.of(new UtxoEntry(2000, TRUE, 1, false));
        assertDoesNotThrow(() -> InputScriptValidator.validateAll(transaction(script), coins, ScriptVerifyFlags.NONE));
        assertThrows(TransactionValidationException.class,
                () -> InputScriptValidator.validateAll(transaction(script), coins, ScriptVerifyFlags.SIGPUSHONLY));
    }
    @Test void nonMinimalPushIsAllowedWithoutMinimalData() {
        byte[] script = {0x4c, 1, 1};
        assertTrue(verify(script, ScriptVerifyFlags.SIGPUSHONLY));
        assertFalse(verify(script, ScriptVerifyFlags.SIGPUSHONLY | ScriptVerifyFlags.MINIMALDATA));
    }
    @Test void numericPushesAndEmptyScriptAreAllowed() {
        assertTrue(verify(new byte[0], ScriptVerifyFlags.SIGPUSHONLY));
        assertTrue(verify(new byte[]{0, 0x4f, 0x51, 0x60}, ScriptVerifyFlags.SIGPUSHONLY));
    }
    @Test void malformedPushAndReservedOpcodeStillFail() {
        assertFalse(verify(new byte[]{0x4c, 2, 1}, ScriptVerifyFlags.SIGPUSHONLY));
        assertTrue(P2shScript.isPushOnly(new byte[]{0x50}));
        assertFalse(verify(new byte[]{0x50}, ScriptVerifyFlags.SIGPUSHONLY));
    }
}
