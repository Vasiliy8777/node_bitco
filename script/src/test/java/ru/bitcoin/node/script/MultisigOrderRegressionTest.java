package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultisigOrderRegressionTest {
    private static final Transaction TX = new Transaction(2,
            List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)),
            List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));

    @Test void malformedTopSignatureMustFailDerBeforeEarlyExit() {
        assertFalse(verify("0000010152000052ae91", ScriptVerifyFlags.DERSIG));
    }
    @Test void unexaminedMalformedBottomSignatureMustNotFailDer() {
        assertTrue(verify("0001010052000052ae91", ScriptVerifyFlags.DERSIG));
    }
    @Test void emptySignatureDoesNotBypassPublicKeyEncodingPolicy() {
        assertFalse(verify("0000510051ae91", ScriptVerifyFlags.STRICTENC));
        assertTrue(verify("0000510051ae91", ScriptVerifyFlags.DERSIG));
    }
    @Test void checkSigAlsoChecksEncodingAndFindAndDeleteForEmptySignature() {
        assertFalse(verify("0000ac91", ScriptVerifyFlags.STRICTENC));
        assertFalse(verify("0000ac91", ScriptVerifyFlags.CONST_SCRIPTCODE));
        assertTrue(verify("0000ac91", ScriptVerifyFlags.DERSIG));
    }
    private static boolean verify(String hex, int flags) {
        return LegacyScriptVerifier.verify(TX, 0, new byte[0], HexFormat.of().parseHex(hex), flags);
    }
}
