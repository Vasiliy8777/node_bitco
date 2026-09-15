package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConstScriptCodeSeparatorTest {
    private static void execute(byte[] script, SignatureVersion version, int flags) {
        var tx = new Transaction(1, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE)), List.of(new TxOut(1,new byte[]{0x51})),new UInt32(0));
        ScriptInterpreter.execute(script,new ScriptMachine(),new ScriptExecutionContext(tx,0,script,flags,1,version));
    }
    @Test void legacyRejectsExecutedAndUnexecutedSeparatorsOnlyWithFlag() {
        for(byte[] script : new byte[][]{{(byte)0xab,0x51},{0,0x63,(byte)0xab,0x68,0x51}}) {
            assertDoesNotThrow(() -> execute(script,SignatureVersion.LEGACY,0));
            assertThrows(ScriptExecutionException.class,
                    () -> execute(script,SignatureVersion.LEGACY,ScriptVerifyFlags.CONST_SCRIPTCODE));
        }
    }
    @Test void witnessV0AllowsSeparatorsWithFlag() {
        assertDoesNotThrow(() -> execute(new byte[]{(byte)0xab,0x51},SignatureVersion.WITNESS_V0,
                ScriptVerifyFlags.CONST_SCRIPTCODE));
    }
    @Test void separatorByteInsidePushIsNotAnOpcode() {
        assertDoesNotThrow(() -> execute(new byte[]{1,(byte)0xab},SignatureVersion.LEGACY,
                ScriptVerifyFlags.CONST_SCRIPTCODE));
    }
}
