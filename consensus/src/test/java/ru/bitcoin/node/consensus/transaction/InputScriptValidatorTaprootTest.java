package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputScriptValidatorTaprootTest {
    @Test void activationMakesInvalidNativeV1SpendFail() {
        var tx = transaction(new byte[0]);
        UtxoView view = point -> Optional.of(new UtxoEntry(1000,
                HexUtils.decode("5120" + "11".repeat(32)), 0, false));
        assertDoesNotThrow(() -> InputScriptValidator.validateAll(tx, view, ScriptVerifyFlags.WITNESS));
        assertThrows(ScriptExecutionException.class, () -> InputScriptValidator.validateAll(tx, view,
                ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.TAPROOT));
    }
    @Test void nativeTaprootRequiresEmptyScriptSig() {
        UtxoView view = point -> Optional.of(new UtxoEntry(1000, HexUtils.decode("5120" + "11".repeat(32)), 0, false));
        assertThrows(TransactionValidationException.class, () -> InputScriptValidator.validateAll(transaction(new byte[]{0}), view,
                ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.TAPROOT));
    }
    @Test void monetaryCheckDoesNotExecuteLegacyScriptsBeforeWitnessValidation() {
        // Monetary validation is independent; the false outer script must still fail script validation.
        UtxoView view = point -> Optional.of(new UtxoEntry(1000, HexUtils.decode("52020000"), 0, false));
        var tx = transaction(new byte[0]);
        assertEquals(100, ContextualTransactionValidator.validateInputs(tx, 1, view).fee());
        assertThrows(TransactionValidationException.class, () -> InputScriptValidator.validateAll(tx, view, ScriptVerifyFlags.WITNESS));
    }
    private static Transaction transaction(byte[] scriptSig) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0)),
                scriptSig, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
    }
}
