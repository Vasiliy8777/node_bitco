package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WitnessOuterScriptTest {
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS;
    @Test void zeroAndNegativeZeroProgramsFailForNativeAndWrappedSpends() {
        for (String hex : List.of("52020000", "52020080")) {
            byte[] program = HexFormat.of().parseHex(hex);
            assertThrows(TransactionValidationException.class, () -> check(program, new byte[0], FLAGS));
            byte[] locking = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(program)) + "87");
            byte[] sig = new byte[program.length+1]; sig[0]=(byte)program.length;
            System.arraycopy(program,0,sig,1,program.length);
            assertThrows(TransactionValidationException.class, () -> check(locking, sig, FLAGS));
        }
        assertDoesNotThrow(() -> check(HexFormat.of().parseHex("52020100"), new byte[0], FLAGS));
    }
    @Test void anchorIsRecognizedOnlyForNativeWitness() {
        byte[] anchor = HexFormat.of().parseHex("51024e73");
        assertEquals(ScriptPubKeyType.ANCHOR, ScriptPubKeyClassifier.classify(anchor));
        assertDoesNotThrow(() -> check(anchor,new byte[0],FLAGS | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM));
        byte[] locking = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(anchor)) + "87");
        assertThrows(ScriptExecutionException.class, () -> check(locking,new byte[]{4,0x51,2,0x4e,0x73},
                FLAGS | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM));
    }
    private static void check(byte[] locking,byte[] sig,int flags) {
        var tx = new Transaction(2,List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)),new UInt32(0)),sig,TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1000,new byte[]{0x51})),new UInt32(0));
        InputScriptValidator.validateAll(tx,out -> Optional.of(new UtxoEntry(2000,locking,1,false)),flags);
    }
}
